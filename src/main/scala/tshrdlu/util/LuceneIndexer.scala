package tshrdlu.util

import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search._
import org.apache.lucene.store._
import org.apache.lucene.analysis.en._
import org.apache.lucene.util.Version
import twitter4j.Status
import scala.collection.JavaConversions._
import java.io.{FileInputStream,BufferedInputStream}
import org.apache.commons.compress.compressors.bzip2._

object LuceneIndexer {
  // Write locally rather than to the Scratch filesystem...
  val index = new SimpleFSDirectory(new java.io.File("lucene-tweets"))

  // Set up Lucene Index...
  val analyzer = new EnglishAnalyzer(Version.LUCENE_41)
  val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
  val writer = new IndexWriter(index, config)
  val parser = new QueryParser(Version.LUCENE_41, "text", analyzer)

  def write(tweets: Iterable[String]) {
    val documents = asJavaIterable(tweets.map({tweet =>
      val doc = new Document()
      doc.add(new TextField("text", tweet, Field.Store.YES))
      doc
    }))
    writer.addDocuments(documents)
    writer.commit()
  }

  def main(args: Array[String]) {
    println("Indexing Tweets...")

    // List of files to Index
    val fileNames = List("/scratch/01683/benwing/corpora/twitter-pull/originals/markov/global2.tweets.2012-07-20.1630.bz2","/scratch/01683/benwing/corpora/twitter-pull/originals/markov/global2.tweets.2012-07-21.1630.bz2")

    // Convert filenames to BZip Input Streams
    val fins = fileNames.map(file => new FileInputStream(file))
    val ins = fins.map(fin => new BufferedInputStream(fin))
    val bzIns = ins.map(in => new BZip2CompressorInputStream(in))

    // Get a line iterator...
    val linesIterators = bzIns.map(bzIn => io.Source.fromInputStream(bzIn).getLines)
    val lines = linesIterators.reduce(_++_)

    var count = 0
    while (lines.hasNext) {
      val tweetJson = lines.next

      // If its a good tweet, write it and count it
      if (!tweetJson.startsWith("{\"delete")) {
        if (count % 100 == 0) println("Processed " + count + " Tweets")
        write(List(tweetJson))
      }

      count += 1
    }
  }

}
