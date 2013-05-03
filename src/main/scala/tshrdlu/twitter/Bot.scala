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
  case class SearchTwitter(query: Query)
  case class SearchTwitterWithUser(query: Query, user: String)
  case class UpdateStatus(update: StatusUpdate)

  def main (args: Array[String]) {
    val system = ActorSystem("TwitterBot")
    val sample = system.actorOf(Props[Sampler], name = "Sampler")
    sample ! Sample
    val bot = system.actorOf(Props[Bot], name = "Bot")
    bot ! Start

    // Check the weather every hour
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

  var userMood = collection.mutable.Map(""->0.0)
  var opinions = collection.mutable.Map[String,Double]()
  var weatherCode = -1
  var weatherMood = 0.0

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
      log.info("Searching With User in Mind... ("+user+")")

      val tweets: Seq[Status] = twitter.search(query.count(100)).getTweets.toSeq

      val curMood = if (userMood.contains(user)) {
                      userMood(user)
                    } else {
                      userMood += user -> weatherMood
                      weatherMood
                    }

      val filteredTweets = filterTweetsByMood(tweets,curMood)
      log.info("Length Of Filtered Tweets: "+filteredTweets.length)

      if (filteredTweets.length == 0) {
        sender ! tweets
      } else {
        sender ! filteredTweets
      }
      
    case UpdateStatus(update) => 
      log.info("Posting update: " + update.getStatus)
      twitter.updateStatus(update)

    case status: Status =>
      log.info("New status: " + status.getText)

      val tweeter = status.getUser.getScreenName

      if (!userMood.contains(tweeter)) {
        userMood += tweeter -> weatherMood
      }

      val replyName = status.getInReplyToScreenName
      if (replyName == username) {

        if (status.getText.contains("Set Mood:")) {
          val text = status.getText
          val moodTarget = text.takeRight(1).toInt
          if (text.takeRight(2).startsWith("1")) {
            userMood = userMood.map(x => (x._1,1.0))
          } else {
            userMood = userMood.map(x => (x._1,moodTarget.toDouble/10))
          }
          log.info("Global Mood Changed")
        } else if (status.getText.contains("getOpinion:")) {
          val opinionItem = status.getText.substring(status.getText.indexOf(":")+1).trim
          log.info("Forming Opinion About: "+opinionItem)

          //Search for opinionitem.
          val opinionTweets = twitter.search(new Query(opinionItem).count(100).lang("en")).getTweets.toSeq
          val opinionSentiment = opinionTweets.map{tweet =>
            val pos = SimpleTokenizer(tweet.getText)
                          .filter{ word => Polarity.posWords(word.toLowerCase) }
                          .length 
            val neg = SimpleTokenizer(tweet.getText)
                          .filter{ word => Polarity.negWords(word.toLowerCase) }
                          .length
            val all = pos+neg+0.001
            if ( all < 1.1 ) 512.0 else (pos.toDouble/(all))
          }
          val opinion = (opinionSentiment.filterNot(x=>x==512.0).sum / opinionSentiment.filterNot(x=>x==512.0).length)+0.1

          ///////////////////////////////////////////////////////////
          opinionTweets.foreach{tweet =>
            val toks = SimpleTokenizer(tweet.getText)
            toks.foreach{word =>
              if (Polarity.posWords(word.toLowerCase)) print(Console.BLUE + word +" "+ Console.RESET)
              else if (Polarity.negWords(word.toLowerCase)) print(Console.RED + word +" "+ Console.RESET)
              else print(Console.WHITE + word +" "+ Console.RESET)
            }
            println()
          }
          /////////////////////////////////////////////////////////////


          log.info("Opinion Of "+opinionItem+": "+opinion)

          if (opinions.contains(opinionItem)) {
            opinions(opinionItem) = opinion
          } else {
            opinions += opinionItem -> opinion
          } 

        } else {
          log.info("Replying to: " + status.getText)
          replierManager ! ReplyToStatus(status)

          val newPosWords = Polarity.posWords ++ opinions.filter(x=>x._2 >= 0.5).keys.toSet
          val newNegWords = Polarity.negWords ++ opinions.filter(x=>x._2 < 0.5).keys.toSet
          val pos = SimpleTokenizer(status.getText)
                        .filter{ word => newPosWords(word.toLowerCase) }
                        .length 
          val neg = SimpleTokenizer(status.getText)
                        .filter{ word => newNegWords(word.toLowerCase) }
                        .length

          val all = pos+neg+0.001

          log.info("Polarity of Incoming Tweet: "+(pos.toDouble/(all)).toString)
          val moodUpdate = pos.toDouble/(all)
          if (all > 2) userMood(tweeter) = (0.2*moodUpdate) + (0.8*userMood(tweeter))
          log.info("Current Mood For "+tweeter+": "+userMood(tweeter))
        }
      }


    case CheckWeather =>
      val cmd = "curl http://api.openweathermap.org/data/2.1/find/city?lat=30.267151&lon=-97.743057&cnt=1"
      val weatherJson = cmd !!
      val idx = weatherJson.indexOf("""weather":[{"id""")
      val code = weatherJson.slice(idx+16,idx+19).toInt
      weatherCode = code
      if(code == 800){
        weatherMood = 0.9
      } else if (code > 802){
        weatherMood = 0.5
      } else if (code < 700){
        weatherMood = 0.1
      }
      replierManager ! SetWeather(code)
      log.info("Checked Weather - New Weather Mood: "+weatherMood)
  }

  /**
  * Filters out tweets that do not correspond the bot's current mood.
  */
  def filterTweetsByMood(tweets: Seq[Status], mood: Double): Seq[Status] = {
    tweets.filter{ tweet =>
      val newPosWords = Polarity.posWords ++ opinions.filter(x=>x._2 >= 0.5).keys.toSet
      val newNegWords = Polarity.negWords ++ opinions.filter(x=>x._2 < 0.5).keys.toSet
      val pos = SimpleTokenizer(tweet.getText)
                      .filter{ word => newPosWords(word.toLowerCase) }
                      .length 
      val neg = SimpleTokenizer(tweet.getText)
                      .filter{ word => newNegWords(word.toLowerCase) }
                      .length 

      val all = pos + neg + 0.001

      val tweetMood = pos/all
    
      // Filter Function
      (tweetMood-0.2 <= mood) && (mood <= tweetMood+0.2) && (all > 1.1)
      
    }
  }

  def onStatus(status: Status) {
    
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
