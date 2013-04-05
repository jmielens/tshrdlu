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

  var mood = 0
  var weatherCode = -1
  var posCount = 1
  var negCount = 1

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
    replierManager ! RegisterReplier(streamReplier)
    replierManager ! RegisterReplier(synonymReplier)
    replierManager ! RegisterReplier(synonymStreamReplier)
    replierManager ! RegisterReplier(bigramReplier)
    replierManager ! RegisterReplier(luceneReplier)
    replierManager ! RegisterReplier(topicModelReplier)
    replierManager ! RegisterReplier(weatherReplier)
  }

  def receive = {
    case Start => streamer.stream.user

    case Shutdown => streamer.stream.shutdown

    case SearchTwitter(query) => 
      val tweets: Seq[Status] = twitter.search(query).getTweets.toSeq
      val filteredTweets = filterTweetsByMood(tweets)
      log.info("Length Of Filtered Tweets: "+filteredTweets.length)


      if (filteredTweets.length < 2) {
        log.info("Getting More Tweets...")
        val tweets2: Seq[Status] = twitter.search(query).getTweets.toSeq
        val filteredTweets2 = filterTweetsByMood(tweets)

        val totalTweets = filteredTweets ++ filteredTweets2
        if (totalTweets.length == 0) {
          log.info("No tweets with correct mood, defaulting to original")
          sender ! tweets
        } else {
          sender ! totalTweets
        }
      } else {
        sender ! filteredTweets
      }
      
    case UpdateStatus(update) => 
      log.info("Posting update: " + update.getStatus)
      twitter.updateStatus(update)

    case status: Status =>
      log.info("New status: " + status.getText)
      val replyName = status.getInReplyToScreenName
      if (replyName == username) {
        log.info("Replying to: " + status.getText)
        replierManager ! ReplyToStatus(status)

        val pos = SimpleTokenizer(status.getText)
                      .filter{ word => Polarity.posWords(word.toLowerCase) }
                      .length 
        val neg = SimpleTokenizer(status.getText)
                      .filter{ word => Polarity.negWords(word.toLowerCase) }
                      .length
        posCount += pos
        negCount += neg
        log.info("Polarity of Incoming Tweet: "+(pos-neg).toString)
        val moodUpdate = posCount.toDouble / (posCount+negCount)
        val newMood = Math.round(moodUpdate*7)-4
        mood = Math.round((newMood + (2*mood))/2)
        log.info("Current Mood: "+mood)
      }


    case CheckWeather =>
      val cmd = "curl http://api.openweathermap.org/data/2.1/find/city?lat=30.267151&lon=-97.743057&cnt=1"
      val weatherJson = cmd !!
      val idx = weatherJson.indexOf("""weather":[{"id""")
      val code = weatherJson.slice(idx+16,idx+19).toInt
      weatherCode = code
      if(code == 800){
        mood = 3
      } else if (code > 802){
        mood = -1
      } else if (code < 700){
        mood = -3;
      }
      replierManager ! SetWeather(code)
      log.info("Checked Weather - New Mood: "+mood)
  }

  /**
  * Filters out tweets that do not correspond the bot's current mood.
  */
  def filterTweetsByMood(tweets: Seq[Status]): Seq[Status] = {
    tweets.filter{ tweet =>
      val pos = SimpleTokenizer(tweet.getText)
                      .filter{ word => Polarity.posWords(word.toLowerCase) }
                      .length 
      val neg = SimpleTokenizer(tweet.getText)
                      .filter{ word => Polarity.negWords(word.toLowerCase) }
                      .length 

      val tweetMood = pos-neg
      if (mood == 3) { // If bot is very positive, accept unlimited positivity
        tweetMood > 3
      } else if (mood == -3) { // If bot is negative, accept unlimited negativity
        tweetMood < -3
      } else {
        (tweetMood-1 <= mood) && (mood <= tweetMood+1)
      }
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
