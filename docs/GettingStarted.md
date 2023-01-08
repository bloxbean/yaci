# Getting Started

### 1. Dependencies

Yaci jars are available on Maven Central. You can use the following dependency in pom.xml or build.gradle to 
include yaci in your Java App. 

**Note :-** Yaci uses slf4j api for logging. To see Yaci's log during runtime, you also need to add one of the
Slf4j binding (slf4j log4j12, slf4j logback etc.). By default, the library has a default log.xml file included in it. So by adding 
log4j binding, you should be able to get library's log messages.

#### Maven

```xml
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>yaci</artifactId>
    <version>{version}</version>
</dependency>
```

#### Gradle

```xml
 implementation('com.bloxbean.cardano:yaci:{version}')
```
The below sections go through some simple use cases using different available apis.

### 2. [Getting Started With Fetcher api](fetchers/FetchersGettingStarted.md)

### 3. [Getting Started With Reactive api](reactive/ReactiveGettingStarted.md)

### 4. [Getting Started With Local Client Providers](local-client-provider.md)

##
