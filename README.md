# tshrdlu -- MOODY Modifications
=======

Authors: **Jim Evans** (jimevans87@gmail.com), **Jason Mielens** (jmielens@utexas.edu)

Original tshrdlu Author: **Jason Baldridge** (jasonbaldridge@gmail.com)


This is a repository for the Twitter bot "MOODY", which is our [final project](https://github.com/utcompling/applied-nlp/wiki/Course-Project) code for the [Applied NLP course](https://github.com/utcompling/applied-nlp/wiki) taught by [Jason Baldridge](http://www.jasonbaldridge.com) at [UT Austin](http://www.utexas.edu).

## Requirements

* Version 1.6 of the Java 2 SDK (http://java.sun.com)
* Sentiment Lexicons, either LIWC or others. See the following section for details.

## Sentiment Lexicons

MOODY uses sentiment lexicons derived from [LIWC](http://www.liwc.net/) dictionaries. These are lexicons are not freely available, but if you have access to them, you can recreate our exact lexicons by taking the following steps.

* From the raw LIWC lexicon, extract words tagged as positive emotion words (posemo) into a file `positive_words.txt.gz` with one word per line.
* Extract the words tagged as anger words (anger) into `angry.txt.gz`
* Extract sad words (sad) into `sad.txt.gz`
* Place these files into the `/src/main/resources/lang/eng/lexicon/` directory.

If you do not have access to the LIWC dictionary, feel free to use any other sentiment lexicon that you do have available, and follow the format above. Note that on a sentiment word, MOODY supports a trailing `*` that acts as a wildcard. For instance, if `sad*` is in a sentiment lexicon, it will match `sadness` as well as `saddest`. 

## Using Offline Corpora

MOODY can make use of an offline corpus of Tweets in order to save on Twitter API hits. We have created a Lucene index of tweets suitable for this purpose that currently sits on the Longhorn cluster at the Texas Advanced Computing Center (TACC).

Provided you are in the correct permissions group, the current codebase will access this index if it is run from Longhorn. If you wish to create a new index, you will have to use `tshrdlu.util.LuceneIndexer` to do so. This will require modifying `src/main/scala/tshrdlu/util/LuceneIndexer.scala` to specify the location and format of your tweets. Contact the authors for assistance.

## Configuring your environment variables

The easiest thing to do is to set the environment variables `JAVA_HOME`
and `TSHRDLU_DIR` to the relevant locations on your system. Set `JAVA_HOME`
to match the top level directory containing the Java installation you
want to use.

Next, add the directory `TSHRDLU_DIR/bin` to your path. For example, you
can set the path in your `.bashrc` file as follows:

	export PATH=$PATH:$TSHRDLU_DIR/bin

Once you have taken care of these three things, you should be able to
build and use tshrdlu.

If you plan to index and search objects using the provided code based
on Lucene, you can customize the directory where on-disk indexes are
stored (the default is the tempdir, check the directory `tshrdlu`) by
setting the environment variable `TSHRDLU_INDEX_DIR`.


## Building the system from source

tshrdlu uses SBT (Simple Build Tool) with a standard directory
structure.  To build tshrdlu, type (in the `TSHRDLU_DIR` directory):

	$ ./build update compile

This will compile the source files and put them in
`./target/classes`. If this is your first time running it, you will see
messages about Scala being downloaded -- this is fine and
expected. Once that is over, the tshrdlu code will be compiled.

To try out other build targets, do:

	$ ./build

This will drop you into the SBT interface. To see the actions that are
possible, hit the TAB key. (In general, you can do auto-completion on
any command prefix in SBT, hurrah!)

To make sure all the tests pass, do:

	$ ./build test

Documentation for SBT is at <http://www.scala-sbt.org/>

Note: if you have SBT already installed on your system, you can
also just call it directly with "sbt" in `TSHRDLU_DIR`.


# Questions or suggestions?

Email The Authors: <jimevans87@gmail.com>,<jmielens@utexas.edu>

Or, create an issue: <https://github.com/jmielens/tshrdlu/issues>
