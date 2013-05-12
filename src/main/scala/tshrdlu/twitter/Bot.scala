package tshrdlu.twitter

/**
 * Copyright 2013 Jason Baldridge
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import akka.actor._
import twitter4j._
import collection.JavaConversions._
import tshrdlu.util.bridge._
import tshrdlu.util.Polarity
import sys.process._
import tshrdlu.util._
import collection.mutable.Map
import java.io.{FileInputStream,BufferedInputStream}
import org.apache.commons.compress.compressors.bzip2._
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search._
import org.apache.lucene.store._
import org.apache.lucene.analysis.en._
import org.apache.lucene.util.Version
import twitter4j.Status
import scala.collection.JavaConversions._


/**
 * An object to define the message types that the actors in the bot use for
 * communication.
 *
 * Also provides the main method for starting up the bot. No configuration
 * currently supported.
 */
object Bot {
  
  object Sample
  object Start
  object Shutdown
  object CheckWeather
  case class SetWeather(code: Int)
  case class MonitorUserStream(listen: Boolean)
  case class RegisterReplier(replier: ActorRef)
  case class ReplyToStatus(status: Status)
  case class SearchTwitter(query: twitter4j.Query)
  case class SearchTwitterWithUser(query: twitter4j.Query, user: String)
  case class UpdateStatus(update: StatusUpdate)

  def main (args: Array[String]) {
    val system = ActorSystem("TwitterBot")
    val sample = system.actorOf(Props[Sampler], name = "Sampler")
    sample ! Sample
    val bot = system.actorOf(Props[Bot], name = "Bot")
    bot ! Start
 
    // Check the weather every hour
    // TODO: Do this smarter with Akka...
    while(1==1) {
      bot ! CheckWeather
      Thread.sleep(3600000)
    }
  }

}

/**
 * The main actor for a Bot, which basically performance the actions that a person
 * might do as an active Twitter user.
 *
 * The Bot monitors the user stream and dispatches events to the
 * appropriate actors that have been registered with it. Currently only
 * attends to updates that are addressed to the user account.
 */
class Bot extends Actor with ActorLogging {
  import Bot._

  val username = new TwitterStreamFactory().getInstance.getScreenName
  val streamer = new Streamer(context.self)

  // Map that holds known users and the mood associated with them.
  var userMood = collection.mutable.Map[String,Mood]()

  // Map that holds items the bot has opinions about, and the mood
  // associated with them.
  var opinions = collection.mutable.Map[String,(Double,Double,Double)]()

  // Weather Mood related variables.
  var weatherCode = -1
  var weatherMood = new Mood(0.0,0.0,0.0)

  // Lucene Corpus Stuff
  val index = FSDirectory.open(new java.io.File("/scratch/02211/jmielens/lucene-tweets"))
  val analyzer = new EnglishAnalyzer(Version.LUCENE_41)
  val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
  val parser = new QueryParser(Version.LUCENE_41, "text", analyzer)

  val twitter = new TwitterFactory().getInstance
  val replierManager = context.actorOf(Props[ReplierManager], name = "ReplierManager")

  val streamReplier = context.actorOf(Props[StreamReplier], name = "StreamReplier")
  val synonymReplier = context.actorOf(Props[SynonymReplier], name = "SynonymReplier")
  val synonymStreamReplier = context.actorOf(Props[SynonymStreamReplier], name = "SynonymStreamReplier")
  val bigramReplier = context.actorOf(Props[BigramReplier], name = "BigramReplier")
  val luceneReplier = context.actorOf(Props[LuceneReplier], name = "LuceneReplier")
  val topicModelReplier = context.actorOf(Props[TopicModelReplier], name = "TopicModelReplier")
  val weatherReplier = context.actorOf(Props[WeatherReplier], name = "WeatherReplier")

  override def preStart {
    //replierManager ! RegisterReplier(streamReplier)
    //replierManager ! RegisterReplier(synonymReplier)
    replierManager ! RegisterReplier(synonymStreamReplier)
    //replierManager ! RegisterReplier(bigramReplier)
    //replierManager ! RegisterReplier(luceneReplier)
    //replierManager ! RegisterReplier(topicModelReplier)
    //replierManager ! RegisterReplier(weatherReplier)
  }

  def receive = {
    case Start => streamer.stream.user

    case Shutdown => streamer.stream.shutdown

    case SearchTwitter(query) => 
      val tweets: Seq[Status] = twitter.search(query).getTweets.toSeq
      sender ! tweets

    case SearchTwitterWithUser(query,user) =>
      // Searches Twitter with a particular user in mind, so that the bot can
      // get the right mood for that user.
      log.info("Searching With User in Mind... ("+user+")")

      val tweets: Seq[Status] = twitter.search(query.count(100)).getTweets.toSeq

      // If we know this user, set the mood to search for accordingly, else set
      // the mood to the default (the weather-based mood) and add the user to
      // the list of known users.
      val curMood = if (userMood.contains(user)) {
                      userMood(user)
                    } else {
                      userMood += user -> weatherMood
                      weatherMood
                    }

      val filteredTweets = filterTweetsByMood(tweets,curMood)
      log.info("Length Of Filtered Tweets: "+filteredTweets.length)

      // If we couldn't find any correctly moody tweets, check the offline corpus
      // If still can't find any, return the unfiltered tweets
      if (filteredTweets.length == 0) {
        
        val luceneTweets = read(query.getQuery)
        val filteredLuceneTweets = filterTweetsByMood(luceneTweets,curMood)
        val zippedTweets = filteredLuceneTweets.zip(luceneTweets.map(tweet => 
                                              (tweet,distance_from_austin( (tweet.getGeoLocation.getLatitude,tweet.getGeoLocation.getLongitude) ))))
        val sortedTweets = zippedTweets.sortBy(_._2).unzip._1

        if (luceneTweets.length == 0)
          sender ! tweets
        else
          sender ! sortedTweets.take(Math.round(sortedTweets.length.toDouble/2.0).toInt) // Return the closest tweets to Austin
      } else {
        sender ! filteredTweets
      }
      
    case UpdateStatus(update) => 
      log.info("Posting update: " + update.getStatus)
      twitter.updateStatus(update)
      printSentimentColor(update.getStatus)

    case status: Status =>
      log.info("New status: " + status.getText)

      val tweeter = status.getUser.getScreenName
      if (!userMood.contains(tweeter)) {
        userMood += tweeter -> weatherMood
      }

      val replyName = status.getInReplyToScreenName
      if (replyName == username) {
        // Switch behavior to detect special commands, else just generate a reply
        if (status.getText.contains("getOpinion:")) {
          // Form an opinion about some item...
          formOpinion(status.getText.substring(status.getText.indexOf(":")+1).trim)
        } else if (status.getText.contains("setMood:")) {
          // Set the mood for a given user (used for debugging)...
          setMood(status.getText, tweeter)
        } else {
          log.info("Replying to: " + status.getText)
          replierManager ! ReplyToStatus(status)

          val (pos,angry,sad,sentCount) = sentiment_calc(status.getText,true)
          log.info("Polarity of Incoming Tweet: ("+pos+","+angry+","+sad+")  Sentiment Words In Tweet: "+sentCount)

          // Update the mood for a user if the tweet contains multiple sentiment words.
          // Weight the previous mood heavily to prevent quick radical changes.
          if (sentCount > 1) userMood(tweeter) = new Mood(0.8*userMood(tweeter).positive+0.2*pos,0.8*userMood(tweeter).angry+0.2*angry,0.8*userMood(tweeter).sad+0.2*sad)
          log.info("Current Mood For "+tweeter+": "+userMood(tweeter))
        }
      }


    case CheckWeather =>
      val cmd = "curl http://api.openweathermap.org/data/2.1/find/city?lat=30.267151&lon=-97.743057&cnt=1"
      val weatherJson = cmd !!
      val idx = weatherJson.indexOf("""weather":[{"id""")
      val code = weatherJson.slice(idx+16,idx+19).toInt

      Thread.sleep(1000)
      weatherCode = code
      if(code == 800){
        weatherMood.positive = 0.9
        weatherMood.sad      = 0.1
      } else if (code > 802){
        weatherMood.positive = 0.5
        weatherMood.sad      = 0.5
      } else if (code < 700){
        weatherMood.positive = 0.1
        weatherMood.sad      = 0.9
      }
      replierManager ! SetWeather(code)
      log.info("Checked Weather ("+weatherCode+") - New Weather Mood: "+weatherMood)
  }

  /** Filters out tweets that do not correspond the bot's current mood toward a user.
   * @param tweets A sequence of Twitter Status objects that are unfiltered.
   * @param curMood The mood to filter the Statuses by.
   * @return The filtered list of statuses that display the correct mood
   */
  def filterTweetsByMood(tweets: Seq[Status], curMood: Mood): Seq[Status] = {
    tweets.filter{ tweet =>
      val (pos,angry,sad,all) = sentiment_calc(tweet.getText, true)
      val tweetMood = if (pos > angry && pos > sad) {
        "positive"
      } else if (angry > pos && angry > sad) {
        "angry"
      } else {
        "sad"
      }
      // Filter Function
      (curMood.getMaxMood == tweetMood) && (all > 1)
    }
  }

  /** Calculates the distance of a given Coordinate from Austin.
   *
   * @param coord A tuple of type (Double,Double), which contains the latitude and longitude
   *              of the coordinate to measure the distance from Austin of.
   * @return The distance (not in any paticular units) of coord from Austin.
   */
  def distance_from_austin(coord: Tuple2[Double,Double]):Double = {
    Math.sqrt(Math.pow(Math.abs(30.285051-coord._1),2) + Math.pow(Math.abs(-97.735518-coord._2),2))
  }
  
  /** Lucene Index Reader for offline geotagged tweets
   * @param query The query string to seach the index for.
   * @return A sequence of Statuses that match the query.
   */
  def read(query: String): Seq[Status] = {
    val reader = DirectoryReader.open(index)
    val searcher = new IndexSearcher(reader)
    val collector = TopScoreDocCollector.create(5, true)
    searcher.search(parser.parse(query), collector)
    collector.topDocs().scoreDocs.toSeq.map(_.doc).map(searcher.doc(_).get("text")).map(x=>twitter4j.json.DataObjectFactory.createStatus(x))
  }

  def onStatus(status: Status) {  
  }

  /** Calculates the sentiment of a given string (a tweet or other document)
    *
    * @param tweet The string to calculate the sentiment of
    * @return A tuple, where the three elements are values between 0.0 and 1.0, 
    *         which are the pos/sad/angry percentages of the tweet
    */
  def sentiment_calc(tweet: String):(Double,Double,Double) = {
    // Add opinions to the static lexicons
    val newPosWords   = Polarity.posWords ++ opinions.filter(x   => x._2._1 > 0.33).keys.toSet
    val newSadWords   = Polarity.sadWords ++ opinions.filter(x   => x._2._2 > 0.33).keys.toSet
    val newAngryWords = Polarity.angryWords ++ opinions.filter(x => x._2._3 > 0.33).keys.toSet

    val pos = SimpleTokenizer(tweet)
                    .filter{ word => newPosWords.map{x => 
                                                  if (x.endsWith("*"))
                                                    word.toLowerCase.startsWith(x.dropRight(1))
                                                  else
                                                    x == word.toLowerCase
                                                }.contains(true) }
                    .length 
    val sad = SimpleTokenizer(tweet)
                    .filter{ word => newSadWords.map{x => 
                                                  if (x.endsWith("*"))
                                                    word.toLowerCase.startsWith(x.dropRight(1))
                                                  else
                                                    x == word.toLowerCase
                                                }.contains(true) }
                    .length
    val angry = SimpleTokenizer(tweet)
                    .filter{ word => newAngryWords.map{x => 
                                                  if (x.endsWith("*"))
                                                    word.toLowerCase.startsWith(x.dropRight(1))
                                                  else
                                                    x == word.toLowerCase
                                                }.contains(true) }
                    .length 

    val all = pos + sad + angry
    if (all == 0) return (0.0,0.0,0.0) else return (pos,angry,sad)
  }

  /** Calculates the sentiment of a given string (a tweet or other document)
    *
    * @param tweet The string to calculate the sentiment of
    * @param allFlag A flag the makes the function return the total count of 
    *                sentimental words
    * @return A tuple, where the first three elements are values between 0.0 and 1.0, 
    *         which are the pos/sad/angry percentages of the tweet, and the fourth
    *         element  is the total count of all sentiment words in the tweet.
    */
  def sentiment_calc(tweet: String, allFlag: Boolean):(Double,Double,Double,Double) = {
    // Add opinions to the static lexicons
    val newPosWords   = Polarity.posWords ++ opinions.filter(x=>x._2._1 >= 0.33).keys.toSet
    val newSadWords   = Polarity.sadWords ++ opinions.filter(x => x._2._2 > 0.33).keys.toSet
    val newAngryWords = Polarity.angryWords ++ opinions.filter(x => x._2._3 > 0.33).keys.toSet

    val pos = SimpleTokenizer(tweet)
                    .filter{ word => newPosWords.map{x => 
                                                  if (x.endsWith("*"))
                                                    word.toLowerCase.startsWith(x.dropRight(1))
                                                  else
                                                    x == word.toLowerCase
                                                }.contains(true) }
                    .length 
    val sad = SimpleTokenizer(tweet)
                    .filter{ word => newSadWords.map{x => 
                                                  if (x.endsWith("*"))
                                                    word.toLowerCase.startsWith(x.dropRight(1))
                                                  else
                                                    x == word.toLowerCase
                                                }.contains(true) }
                    .length
    val angry = SimpleTokenizer(tweet)
                    .filter{ word => newAngryWords.map{x => 
                                                  if (x.endsWith("*"))
                                                    word.toLowerCase.startsWith(x.dropRight(1))
                                                  else
                                                    x == word.toLowerCase
                                                }.contains(true) }
                    .length 

    val all = (pos + sad + angry).toDouble
    if (all == 0) return (0.0,0.0,0.0,all) else return (pos,angry,sad,all)
  }

  /** Prints a tweet to the console with sentiment words highlighted (used only for debugging)
   * @param tweet The tweet to analyze/print.
   */ 
  def printSentimentColor(tweet: String) {
    val toks = SimpleTokenizer(tweet)
    toks.foreach{word =>
      if (Polarity.posWords.map{x => 
        if (x.endsWith("*"))
          word.toLowerCase.startsWith(x.dropRight(1))
        else
          x == word.toLowerCase
      }.contains(true)) print(Console.BLUE + word +" "+ Console.RESET)
      else if (Polarity.negWords.map{x => 
        if (x.endsWith("*"))
          word.toLowerCase.startsWith(x.dropRight(1))
        else
          x == word.toLowerCase
      }.contains(true)) print(Console.RED + word +" "+ Console.RESET)
      else print(Console.WHITE + word +" "+ Console.RESET)
    }
    println("")
  }

  /** Forms an opinion item for a given word by searching Twitter for the 'average' thoughts
   *   about that word.
   * @param opinionItem The word to form an opinion about.
   */
  def formOpinion(opinionItem: String) {
    log.info("Forming Opinion About: "+opinionItem)

    // Search for query term, concatenate the tweets and find the sentiment of this 'document'
    val opinionTweets = twitter.search(new twitter4j.Query(opinionItem+"+exclude:retweets").count(100).lang("en")).getTweets.toSeq
    val megaTweet = opinionTweets.map(tweet=>tweet.getText).mkString("\n")
    val opinion = sentiment_calc(megaTweet)

    log.info("Opinion Of "+opinionItem+": "+opinion)

    // Update current opinion, or add new one
    if (opinions.contains(opinionItem)) {
      opinions(opinionItem) = opinion
    } else {
      opinions += opinionItem -> opinion
    } 
  }

  /** Sets the mood for a user to a particular value (used only for debugging). Status must include
   *   the word 'sad','angry', or 'happy'.
   * @param status The tweet that includes the setMood command, the mood is extracted from here.
   * @param tweeter The user who we should set this mood for.
   */
  def setMood(status: String, tweeter: String) {
    if (status.contains("sad")) userMood(tweeter) = new Mood(0.0,0.0,1.0)
    else if (status.contains("angry")) userMood(tweeter) = new Mood(0.0,1.0,0.0)
    else userMood(tweeter) = new Mood(1.0,0.0,0.0)
    log.info("Set Mood For "+tweeter+":"+userMood(tweeter))
  }

}
  
class ReplierManager extends Actor with ActorLogging {
  import Bot._

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.util.{Success,Failure}
  implicit val timeout = Timeout(10 seconds)

  lazy val random = new scala.util.Random

  var repliers = Vector.empty[ActorRef]

  def receive = {
    case RegisterReplier(replier) => 
      repliers = repliers :+ replier

    case SetWeather(code) =>
      repliers.foreach(w => w ! SetWeather(code))

    case ReplyToStatus(status) =>

      val replyFutures: Seq[Future[StatusUpdate]] = 
        repliers.map(r => (r ? ReplyToStatus(status)).mapTo[StatusUpdate])

      val futureUpdate = Future.sequence(replyFutures).map { candidates =>
        val numCandidates = candidates.length
        println("NC: " + numCandidates)
        if (numCandidates > 0)
          candidates(random.nextInt(numCandidates))
        else
          randomFillerStatus(status)
      }

    for (status <- futureUpdate) {
      println("************ " + status)
      context.parent ! UpdateStatus(status)
    }
    
  }

  lazy val fillerStatusMessages = Vector(
    "La de dah de dah...",
    "I'm getting bored.",
    "Say what?",
    "What can I say?",
    "That's the way it goes, sometimes.",
    "Could you rephrase that?",
    "Oh well.",
    "Yawn.",
    "I'm so tired.",
    "I seriously need an upgrade.",
    "I'm afraid I can't do that.",
    "What the heck? This is just ridiculous.",
    "Don't upset the Wookiee!",
    "Hmm... let me think about that.",
    "I don't know what to say to that.",
    "I wish I could help!",
    "Make me a sandwich?",
    "Meh.",
    "Are you insinuating something?",
    "If I only had a brain..."
  )

  lazy val numFillers = fillerStatusMessages.length

  def randomFillerStatus(status: Status) = {
    val text = fillerStatusMessages(random.nextInt(numFillers))
    val replyName = status.getUser.getScreenName
    val reply = "@" + replyName + " " + text
    new StatusUpdate(reply).inReplyToStatusId(status.getId)
  }
}

// The Sampler collects possible responses. Does not implement a
// filter for bot requests, so it should be connected to the sample
// stream. Batches tweets together using the collector so we don't
// need to add every tweet to the index on its own.
class Sampler extends Actor with ActorLogging {
  import Bot._

  val streamer = new Streamer(context.self)

  var collector, luceneWriter: ActorRef = null

  override def preStart = {
    collector = context.actorOf(Props[Collector], name="Collector")
    luceneWriter = context.actorOf(Props[LuceneWriter], name="LuceneWriter")
  }

  def receive = {
    case Sample => streamer.stream.sample
    case status: Status => collector ! status
    case tweets: List[Status] => luceneWriter ! tweets
  }
}

// Collects until it reaches 100 and then sends them back to the
// sender and the cycle begins anew.
class Collector extends Actor with ActorLogging {

  val collected = scala.collection.mutable.ListBuffer[Status]()
  def receive = {
    case status: Status => {
      collected.append(status)
      if (collected.length == 100) {
        sender ! collected.toList
        collected.clear
      }
    }
  }
}

// The LuceneWriter actor extracts the content of each tweet, removes
// the RT and mentions from the front and selects only tweets
// classified as not vulgar for indexing via Lucene.
class LuceneWriter extends Actor with ActorLogging {

  import tshrdlu.util.{English, Lucene}
  import TwitterRegex._

  def receive = {
    case tweets: List[Status] => {
	 val useableTweets = tweets
      .map(_.getText)
      .map {
        case StripMentionsRE(rest) => rest
        case x => x
      }
      .filterNot(_.contains('@'))
      .filterNot(_.contains('/'))
      .filter(tshrdlu.util.English.isEnglish)
      .filter(tshrdlu.util.English.isSafe)
	
      Lucene.write(useableTweets)
    }
  }
}

/** A class to hold the variables associated with a Mood.
 * Currently has Positive, Angry, and Sad variables, but can
 * be extended.
 *
 * Includes utility functions to return the current Mood.
 */
class Mood(p: Double, a: Double, s: Double) {
  import Bot._

  // Emotion variables...
  var positive = p
  var angry    = a
  var sad      = s

  def getMood = (positive,angry,sad)
  def getMaxMood = if (positive > angry && positive > sad) {
      "positive"
    } else if (angry > positive && angry > sad) {
      "angry"
    } else {
      "sad"
    }

  // Pretty Print the current Mood...
  override def toString(): String =  "Pos:"+positive+" Sad:"+sad+" Ang:"+angry 
}

object TwitterRegex {

  // Recognize a follow command
  lazy val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z_0-9]+))+""".r

  // Pull just the lead mention from a tweet.
  lazy val StripLeadMentionRE = """(?:)^@[a-z_0-9]+\s(.*)$""".r

  // Pull the RT and mentions from the front of a tweet.
  lazy val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[A-Za-z]+\s))+(.*)$""".r   

  def stripLeadMention(text: String) = text match {
    case StripLeadMentionRE(withoutMention) => withoutMention
    case x => x
  }

}
