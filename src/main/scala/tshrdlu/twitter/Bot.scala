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
import scala.concurrent.ops._
import sys.process._
import cc.mallet._
import java.io._

/**
 * Stand-alone Object used to follow all the followers of a given twitter
 * user, provided the follower's name ends in '_anlp'
 */
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
  * A bot that responds to be mentioned in the stream by paraphrase
  * the tweet and replying. Also optionally listens to health related
  * tweets with links and analyzes the content of those links -- Classifying
  * them as either Skeptic or Pseudoscience based.
  *
  * @param listen Whether or not to monitor health-related tweets
  */
class JJBot (listen: Boolean) extends TwitterInstance with StreamInstance {
  stream.addListener(new JJGen(twitter,listen))
}

/**
 * Companion object for JJBot.
 */
object JJBot {
    def main(args: Array[String]) {
      var listen = false
      if (args.size > 0) {
        listen = args(0).toBoolean
      }
      val bot = new JJBot(listen)
      bot.stream.user
    }
}

/**
  * Provides implementation for the behaviors the JJBot requires. See individual
  * functions for specific details.
  *
  * @param twitter Twitter instance
  * @param listen Whether to listen for health-related tweets
  */
class JJGen(twitter: Twitter, listen: Boolean) 
extends StatusListenerAdaptor with UserStreamListenerAdaptor {
  import chalk.util.SimpleTokenizer
  import collection.JavaConversions._

  println("Starting JJBot.")

  // Basic Variables
  val username = twitter.getScreenName
  var latestVaccSearchId = 0L   // Stores the highest Tweet ID we've seen,
                                // Used to ensure next tweets we get are newer.
  val LinkExtract = """.*(http[\S]+).*""".r

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
  
  // If we are listening for health-related tweets, then spawn a new thread to do that regularly.
  if (listen) {
    println("Spawning periodic searcher...")
    regularExecute(vaccineLinkSearch,1800)
  }

  println("Ready for tweets.")

  /**
    * Spawns a new thread that loops forever and calls a supplied callback function regularly.
    *
    * @param callback A function with no arguments that returns Unit - called repeatedly until program exits.
    * @param time The number of seconds between callback function calls.
    */
  def regularExecute(callback: () => Unit, time: Int) {
    spawn {
      while (true) { callback(); Thread sleep (time*1000) }
    }
  }

  def vaccineLinkSearch(): Unit = {
    println("New Vaccine Link Search...")

    // Build Query for tweets mentioning 'vaccine' and containing
    // a link. Only get tweets newer than previously seen.
    val Q = new Query("http vaccine")
    Q.setSinceId(latestVaccSearchId)
    val vaccSearch = twitter.search(Q)
    val vaccTweets = vaccSearch.getTweets
    
    if (vaccTweets.size > 0) {
      println("Found New Vaccine Links...")

      // Extract Link and render text from it using W3M
      val vaccLinkToReply = vaccTweets.head
      val LinkExtract(link) = vaccLinkToReply.getText
      println("LINK: "+link)
      val cmd = "./bin/w3m -dump "+link
      val linkContentTmp = cmd !!

      val linkContent = """\s+""".r.replaceAllIn(linkContentTmp," ")

      // Save Linked Page
      var out_file = new java.io.FileOutputStream("vaccineLinkPage")
      var out_stream = new java.io.PrintStream(out_file)
      out_stream.print("vacc1 Vaccine "+linkContent)
      out_stream.close

      val ois = new ObjectInputStream (new FileInputStream (new File ("src/main/resources/alt.classifier")))
      val classifier = ois.readObject().asInstanceOf[cc.mallet.classify.Classifier]
      ois.close()

      val reader = new cc.mallet.pipe.iterator.CsvIterator(new FileReader("vaccineLinkPage"),
                            "(\\w+)\\s+(\\w+)\\s+(.*)",
                            3, 2, 1);  // (data, label, name) field indices    


        // Create an iterator that will pass each instance through                                         
        //  the same pipe that was used to create the training data                                        
        //  for the classifier.                                                                            
      val instances = classifier.getInstancePipe().newIteratorFrom(reader);

        // Classifier.classify() returns a Classification object                                           
        //  that includes the instance, the classifier, and the                                            
        //  classification results (the labeling). Here we only                                            
        //  care about the Labeling.                                                                       
        
      val labeling = classifier.classify(instances.next()).getLabeling()
      val label = labeling.getLabelAtRank(0)

      if (label.toString == "skeptic") {
        println("skeptic")
        val reply = new StatusUpdate("This sounds pretty legit to me: " + link)
        twitter.updateStatus(reply)
      } else {
        println("pseudo")
        val reply = new StatusUpdate("I read this article, not sure what to think.. " + link)
        twitter.updateStatus(reply)
      }
         
      // Update latest tweet counter
      latestVaccSearchId = vaccSearch.getMaxId()
    } else {
      println("No New Vaccine Links!")
    }
  }

  // Recognize a follow command
  val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z]+))+""".r

  // Pull the RT and mentions from the front of a tweet.
  val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[a-z]+\s))+(.*)$""".r   
  override def onStatus(status: Status) {
    println("New status: " + status.getText)
    val replyName = status.getInReplyToScreenName
    if (replyName == username) {
      println("*************")
      println("New reply: " + status.getText)
      val tmp = doActionGetReply(status)
      if (tmp.toString != "NO.") {
        val text = "@" + status.getUser.getScreenName + " " + tmp
        println("Replying: " + text)
        val reply = new StatusUpdate(text).inReplyToStatusId(status.getId)
        twitter.updateStatus(reply)
      }
    }
  }
 
  /**
   * A method that possibly takes an action based on a status
   * it has received, and then produces a response.
   */
  def doActionGetReply(status: Status) = {
    // Pull just the lead mention from a tweet.
    val StripLeadMentionRE = """(?:)^@[a-z]+\s(.*)$""".r
    
    val text = status.getText.toLowerCase
    val followMatches = FollowRE.findAllIn(text)
    
    if (!followMatches.isEmpty) {
      // Follow Behavior
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
      "OK. I followed " + followSet.map("@"+_).mkString(" ") + "."  
    } else {
      // If we are not supposed to follow anyone...
      val rnd = new scala.util.Random(System.currentTimeMillis())
      val r = rnd.nextDouble()

      // With probability 0.1, paraphrase the incoming tweet, otherwise use the generic reply
      if (r > 0.9) {
        // Paraphrase Behavior
        try {
          val withoutMention = text
          val tokText = SimpleTokenizer(text).drop(1)

          // The reply is a map from the original tokens to the synonyms,
          // and short words are returned unaltered.
          val replyText = tokText.map{word =>
            if (word.length < 4) word
            else getSynonym(word)
          }.mkString(" ")
          
          // Output new Status
          "So in other words: "+replyText
        
        } catch { 
          case _: Throwable => "I have no idea."
        }
      } else {
        // Generic Reply
        try {
          val withoutMention = text

          // Get a list of vectors of synonyms for two long words 
          val wordList = 
            SimpleTokenizer(withoutMention)
              .drop(1)
              .filter(_.length > 3)
              .filterNot(_ == "what")
              .filterNot(_ == "where")
              .toSet
              .take(2)
              .toList
              .map(x=>getSynonyms(x,5))

          //wordList.foreach(w => println(w))

          // Build a query, such that a returned tweet has a synonym of each long word.
          // for instance "blue house" => (cyan OR navy OR sad) AND (domicile OR residence)
          val q = wordList.map(x=>x.mkString(" OR ")).map(x=>"("+x+")").mkString(" AND ")
          println("Query: " +q)

          // Search using our built query
          val statusList = twitter.search(new Query(q)).getTweets.toList
          val prospectiveReply = extractText(statusList)

          // If we couldn't find any tweets with the synonyms, back-off to a search for
          // the original words. This happens because in the synonym match we require multiple
          // words (see the example above) in a tweet, which limits the number of returned tweets. 
          if (prospectiveReply == "NO.") {
            try {
              val withoutMention = text
              val statusList =
                SimpleTokenizer(withoutMention)
                .drop(1)
                .filter(_.length > 3)
                .filter(_.length < 10)
                .sortBy(- _.length)
                .toSet
                .take(3)
                .toList
                .flatMap(w => twitter.search(new Query(w)).getTweets)
                extractText(statusList)
            } catch {
              // If we can't find any tweets, try and prompt the original tweeter
              // to rephrase and possibly elaborate.
              case _: Throwable => "What exactly do you mean by that?"
            }
          } else {
            // Return the found reply with synonyms
            prospectiveReply
          }

        } catch { 
          case _: Throwable => "NO."
        }
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

  /**
    * Returns a random synonym for a provided word. If the thesaurus
    * does not contain the word, this function simply returns the word itself.
    *
    * @param word The word to find a synonym for.
    */
  def getSynonym(word: String):String = {
    val cands = synonymMap(word)
    val rnd = new scala.util.Random(System.currentTimeMillis())
    val r = rnd.nextInt(cands.length)
    cands(r) 
  }

  /**
    * Returns a number of synonyms for a given word as a vector of strings.
    *
    * @param word The word to find synonyms for.
    * @param num How many synonyms are desired.
    */
  def getSynonyms(word: String, num: Int):Vector[String] = {
    val cands = synonymMap(word)
    cands.take(num).toVector
  }

}