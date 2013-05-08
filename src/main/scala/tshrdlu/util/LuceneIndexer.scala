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
  val index = new SimpleFSDirectory(new java.io.File("lucene"))
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
    val fin = new FileInputStream("/scratch/01683/benwing/corpora/twitter-pull/originals/markov/spritzer.tweets.2012-09-13.0239.bz2")
    val in = new BufferedInputStream(fin)
    val bzIn = new BZip2CompressorInputStream(in)

    val lines = io.Source.fromInputStream(bzIn).getLines

    var count = 0
    while (lines.hasNext) {
      val tweetJson = lines.next

      if (!tweetJson.startsWith("{\"delete")) {
        val tweet = twitter4j.json.DataObjectFactory.createStatus(tweetJson)
       if (count % 1000 == 0) println(tweet.getText)
        write(List(tweet.getText))
      }

      count += 1
    }
  }

}
