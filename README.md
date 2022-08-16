# Yaci 
A Cardano Mini Protocols implementation in Java

## Status

| mini protocol            | initiator                                                         |
|--------------------------|-------------------------------------------------------------------|
| `n2n Handshake`          | Done                                                              | 
| `n2n Block-Fetch`        | Done                                                              |     
| `n2n Chain-Sync`         | Done                                                              | 
| `n2n TxSubmission`       | In Progress                                                       | 
| `n2n Keep-Alive`         | Not started                                                       | 
| `n2c Handshake`          | Not started                                                       | 
| `n2c Chain-Sync`         | Not started                                                       | 
| `n2c Local TxSubmission` | Not started                                                       | 
| `n2c Local State Query`  | Not started                                                       |


| Other tasks              | Status                                   |
|--------------------------|------------------------------------------|
| `Block Parsing`          | Tx Inputs, Tx Outputs, MultiAssets, Mint |
| `Eras`                   | Shelley, Alonzo, Babbage                 |   
 

## Build

```
$> git clone https://github.com/bloxbean/yaci-core
$> ./gradlew clean build
``` 
