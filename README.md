# tshrdlu with Synonyms
=======

Authors: **Jim Evans and Jason Mielens** ( {j.s.evans,jmielens}@utexas.edu )

This is a fork of the original tshrdlu repository used for a class project. Compared to the original tshrdlu, our version makes use of synonyms in interesting ways and also periodically tweets spontaneously.

This project makes use of the sbt build system. To run, start sbt, compile, and issue the following command:

run-main tshrdlu.twitter.JJBot {listen}

Where {listen} is an optional parameter that is either 'true' or 'false' and determine whether the bot listens for health-related tweets with links and attempts to classify them.

## Requirements

* Version 1.6 of the Java 2 SDK (http://java.sun.com)


# Questions or suggestions?

Email us: <j.s.evans@utexas.edu> or <jmielens@utexas.edu>

Or, create an issue: <https://github.com/jmielens/tshrdlu/issues>
