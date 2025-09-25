#!/bin/bash

mkdir -p out
cp -r src/main/java/* out/

cd out
jar cvf ../ash-collector.jar .
cd ..

if [ -f "ash-collector.jar" ]; then
    echo "JAR created: ash-collector.jar"
else
    echo "Build failed"
    exit 1
fi