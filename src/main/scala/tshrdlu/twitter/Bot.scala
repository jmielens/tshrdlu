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

import twitter4j._
import collection.JavaConversions._


object ClassFollowers extends TwitterInstance {

  def main(args: Array[String]) {
    var cursor = -1
    val screenName = args(0)

    while (cursor != 0) {
      val followerNames = twitter.getFollowersIDs(screenName,cursor)
      val followerIDs = followerNames.getIDs()
      val userNames = followerIDs.map(x=>twitter.showUser(x).getScreenName()).filter(x=>x.endsWith("_anlp"))

      userNames.filterNot(x=>x==twitter.getScreenName).foreach(twitter.createFriendship)
      cursor = followerNames.getNextCursor().toInt
    }


  }

}

/**
 * Base trait with properties default for Configuration.
 * Gets a Twitter instance set up and ready to use.
 */
trait TwitterInstance {
  val twitter = new TwitterFactory().getInstance
}


/**
  *
  */
class MadLibBot extends TwitterInstance with StreamInstance {
  stream.addListener(new MadLibGen(twitter))
}

object MadLibBot {
    def main(args: Array[String]) {
      val bot = new MadLibBot
      bot.stream.user
    }
}

/**
 * A bot that can monitor the stream and also take actions for the user.
 */
class ReactiveBot extends TwitterInstance with StreamInstance {
  stream.addListener(new UserStatusResponder(twitter))
}

/**
 * Companion object for ReactiveBot with main method.
 */
object ReactiveBot {

  def main(args: Array[String]) {
    val bot = new ReactiveBot
    bot.stream.user
    
    // If you aren't following a lot of users and want to get some
    // tweets to test out, use this instead of bot.stream.user.
    //bot.stream.sample
  }
}

class MadLibGen(twitter: Twitter) 
extends StatusListenerAdaptor with UserStreamListenerAdaptor {
  import chalk.util.SimpleTokenizer
  import collection.JavaConversions._

  println("Starting MadLibBot.")

  val username = twitter.getScreenName

  // Build Thesaurus
  println("Building Thesaurus...")
  val thesLines = io.Source.fromFile("src/main/resources/dict/en_thes").getLines.toVector
  val thesWords = thesLines.zipWithIndex.filter(!_._1.contains("("))
  val thesList  = thesWords.unzip._1.map(x => x.split("\\|").head)
  val tmpMap = thesWords.map{ w =>
    val lineNum = w._2
    val senses = w._1.split("\\|").tail.head.toInt

    val range = (lineNum + 1) to (lineNum + senses)

    range.map{
      thesLines(_)
    }
  }

  val synonymMap = thesList.zip(tmpMap).toMap.mapValues{ v => v.flatMap{ x=> x.split("\\|").filterNot(_.contains("("))}}.withDefault(x=>Vector(x.toString))
  println("Built Thesaurus with "+synonymMap.size+" words.")
  println("Ready.")

  override def onStatus(status: Status) {
    val text = status.getText.toLowerCase

    val tokText = SimpleTokenizer(text)

    println("New status: " + text)
    val replyName = status.getInReplyToScreenName
    if (replyName == username) {
      println("*************")

      val replyText = tokText.map{word =>
        if (word.length < 3) word
        else getSynonym(word)
      }.mkString(" ")
      
      println("New reply: " + status.getText)
      val text = "@" + status.getUser.getScreenName + " " + replyText
      println("Replying: " + text)
      val reply = new StatusUpdate(text).inReplyToStatusId(status.getId)
      twitter.updateStatus(reply)
    }    

  }

  def getSynonym(word: String):String = {
    val cands = synonymMap(word)
    val rnd = new scala.util.Random(System.currentTimeMillis())
    val r = rnd.nextInt(cands.length)
    cands(r) 
  }

}

/**
 * A listener that looks for messages to the user and replies using the
 * doActionGetReply method. Actions can be doing things like following,
 * and replies can be generated just about any way you'd like. The base
 * implementation searches for tweets on the API that have overlapping
 * vocabulary and replies with one of those.
 */
class UserStatusResponder(twitter: Twitter) 
extends StatusListenerAdaptor with UserStreamListenerAdaptor {

  import chalk.util.SimpleTokenizer
  import collection.JavaConversions._

  val username = twitter.getScreenName

  // Recognize a follow command
  lazy val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z]+))+""".r

  // Pull just the lead mention from a tweet.
  lazy val StripLeadMentionRE = """(?:)^@[a-z]+\s(.*)$""".r

  // Pull the RT and mentions from the front of a tweet.
  lazy val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[a-z]+\s))+(.*)$""".r   
  override def onStatus(status: Status) {
    println("New status: " + status.getText)
    val replyName = status.getInReplyToScreenName
    if (replyName == username) {
      println("*************")
      println("New reply: " + status.getText)
      val text = "@" + status.getUser.getScreenName + " " + doActionGetReply(status)
      println("Replying: " + text)
      val reply = new StatusUpdate(text).inReplyToStatusId(status.getId)
      twitter.updateStatus(reply)
    }
  }
 
  /**
   * A method that possibly takes an action based on a status
   * it has received, and then produces a response.
   */
  def doActionGetReply(status: Status) = {
    val text = status.getText.toLowerCase
    val followMatches = FollowRE.findAllIn(text)
    if (!followMatches.isEmpty) {
      val followSet = followMatches
	.next
	.drop(1)
	.split("\\s")
	.map {
	  case "me" => status.getUser.getScreenName
	  case screenName => screenName.drop(1)
	}
	.toSet
      followSet.foreach(twitter.createFriendship)
      "OK. I FOLLOWED " + followSet.map("@"+_).mkString(" ") + "."  
    } else {
      
      try {
	val StripLeadMentionRE(withoutMention) = text
	val statusList = 
	  SimpleTokenizer(withoutMention)
	    .filter(_.length > 3)
	    .toSet
	    .take(3)
	    .toList
	    .flatMap(w => twitter.search(new Query(w)).getTweets)
	extractText(statusList)
      }	catch { 
	case _: Throwable => "NO."
      }
    }
  
  }

  /**
   * Go through the list of Statuses, filter out the non-English ones,
   * strip mentions from the front, filter any that have remaining
   * mentions, and then return the head of the set, if it exists.
   */
  def extractText(statusList: List[Status]) = {
    val useableTweets = statusList
      .map(_.getText)
      .map {
	case StripMentionsRE(rest) => rest
	case x => x
      }
      .filterNot(_.contains('@'))
      .filter(tshrdlu.util.English.isEnglish)

    if (useableTweets.isEmpty) "NO." else useableTweets.head
  }

}

