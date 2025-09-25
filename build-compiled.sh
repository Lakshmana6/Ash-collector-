#!/bin/bash

echo "Building ash collector..."

rm -rf out
mkdir -p out

javac -cp "/home/qe/DreamBot/BotData/client.jar" -d out src/main/java/com/example/dreambot/AshCollector.java

if [ $? -ne 0 ]; then
    echo "Compilation failed"
    exit 1
fi

jar cvf ash-collector.jar -C out .

if [ ! -f "ash-collector.jar" ]; then
    echo "JAR creation failed"
    exit 1
fi

DREAMBOT_DIR="/home/qe/DreamBot/Scripts"
if [ -d "$DREAMBOT_DIR" ]; then
    cp ash-collector.jar "$DREAMBOT_DIR/"
    echo "Deployed to DreamBot"
else
    echo "DreamBot directory not found"
    exit 1
fi

echo "Build complete"