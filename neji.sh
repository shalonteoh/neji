#!/bin/bash
cp=target/neji-2.0.2-jar-with-dependencies.jar:$CLASSPATH
MEMORY=6G
JAVA_COMMAND="java -Xmx$MEMORY -Dfile.encoding=UTF-8 -classpath $cp"
CLASS=pt.ua.tm.neji.cli.Main

$JAVA_COMMAND $CLASS $*
