<div align="center">
<img src="static/yaci.png" width="200">

<h4>A Cardano Mini Protocols implementation in Java</h4>

[![Clean, Build](https://github.com/bloxbean/yaci-core/actions/workflows/build.yml/badge.svg)](https://github.com/bloxbean/yaci-core/actions/workflows/build.yml)
</div>

## Overview
Yaci is a Java-based Cardano mini-protocol implementation that allows users to connect to a remote or local Cardano node
and interact with it in a variety of ways. With Yaci's simple APIs, you can listen to incoming blocks in real-time, fetch 
previous blocks, query information from a local node, monitor the local mempool, and submit transactions to a local node.

**Previous Releases :** 
- [0.1.14](https://github.com/bloxbean/yaci/releases/tag/v0.1.14) (**Cardano Node 8.1.1** support and Compatible with Cardano Client Lib 0.4.3)
- [0.2.5](https://github.com/bloxbean/yaci/releases/tag/v0.2.5) (Compatible with Cardano Client Lib 0.5.0)

**Latest Release :**
- [0.3.4.1](https://github.com/bloxbean/yaci/releases/tag/v0.3.4.1) (Supports Conway era) 

  **Development Branch:** main

## Dependencies

Maven

```xml
<dependency>
    <groupId>com.bloxbean.cardano</groupId>
    <artifactId>yaci</artifactId>
    <version>{version}</version>
</dependency>
```

Gradle

```xml
 implementation('com.bloxbean.cardano:yaci:{version}')
```

## How to Use?

### [Documentation](docs/README.md)

### [Getting Started Guides](docs/GettingStarted.md)

## Status

| mini protocol            | initiator      |
|--------------------------|----------------|
| `n2n Handshake`          | Done           | 
| `n2n Block-Fetch`        | Done           |     
| `n2n Chain-Sync`         | Done           | 
| `n2n TxSubmission`       | In Progress    | 
| `n2n Keep-Alive`         | Done    | 
| `n2c Handshake`          | Done           | 
| `n2c Chain-Sync`         | Done           | 
| `n2c Local TxSubmission` | Done           | 
| `n2c Local State Query`  | Partially Done |
| `n2c Local Tx Monitor`   | Done   |


| Other tasks              | Status                                                        |
|--------------------------|---------------------------------------------------------------|
| `Block Parsing`          | Tx Inputs, Tx Outputs, MultiAssets, Mint, Certificate         |
| `Eras`                   | Done (Byron, Shelley, Alonzo, Babbage, Conway) |   
|                          |                                                               |


## Build

```
$> git clone https://github.com/bloxbean/yaci
$> ./gradlew clean build
``` 

# Any questions, ideas or issues ?

- Create a Github [Issue](https://github.com/bloxbean/yaci/issues)
- [Discord Server](https://discord.gg/JtQ54MSw6p)

# Support from YourKit

YourKit has generously granted the BloxBean projects an Open Source licence to use their excellent Java Profiler.

![YourKit](https://www.yourkit.com/images/yklogo.png)

YourKit supports open source projects with innovative and intelligent tools
for monitoring and profiling Java and .NET applications.
YourKit is the creator of <a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>,
<a href="https://www.yourkit.com/.net/profiler/">YourKit .NET Profiler</a>,
and <a href="https://www.yourkit.com/youmonitor/">YourKit YouMonitor</a>
