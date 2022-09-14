# Getting Started

### 1. Dependencies

Yaci Core Jar is available on Maven Central. You can use the following dependency in pom.xml or build.gradle to 
include yaci-core in your Java App. 

**Note :-** Yaci-core uses slf4j api for logging. To see Yaci Core's log during runtime, you also need to add one of the
Slf4j binding (slf4j log4j12, slf4j logback etc.). By default, the library has a default log.xml file included in it. So by adding 
log4j binding, you should be able to get yaci-core's log messages.

#### Maven

```xml
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>yaci-core</artifactId>
    <version>{version}</version>
</dependency>
```

#### Gradle

```xml
 implementation('com.bloxbean.cardano:yaci-core:{version}')
```
The below sections go through some simple use cases using different available apis.

### 2. [Getting Started With Fetcher api](fetchers/FetchersGettingStarted.md)

### 3. [Getting Started With Reactive api](reactive/ReactiveGettingStarted.md)


