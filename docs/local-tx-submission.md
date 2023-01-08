# Local Tx Submission Client

Local Tx submission protocol is used to submit transaction to a local Cardano node. A signed transaction
can be serialized and submitted to the network for execution.

Yaci provides a helper class ``LocalTxSubmissionClient`` which simplifies transaction submission. 

## Get LocalTxSubmissionClient

Create a ``LocalClientProvider`` and get ``LocalTxSubmissionClient``

```java
 LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
 LocalTxSubmissionClient localTxSubmissionClient = localClientProvider.getTxSubmissionClient();
 
 localClientProvider.start();
```

## Submit tx and receive response through listener

To listen to transaction result, you need to first add a ``LocalTxSubmissionListener``.

```java
localClientProvider.addTxSubmissionListener(new LocalTxSubmissionListener() {
    @Override
    public void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {
        System.out.println("TxId : " + txSubmissionRequest.getTxHash());
    }

    @Override
    public void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {
        System.out.println("Rejected: " + msgRejectTx.getReasonCbor());
    }
});
```

Serialize a transaction and create ``TxSubmissionRequest`` using tx bytes. Now use ``submitTxCallback`` method to
submit the transaction.

```java
TxSubmissionRequest txnRequest = new TxSubmissionRequest(txBytes);
localTxSubmissionClient.submitTxCallback(txnRequest);
```

## Handling re-connection

Sometime application needs to know when the connection is established or re-established before submitting 
the transaction. This can be done by setting a ``LocalClientProviderListener`` in LocalClientProvider.

```java
localClientProvider.setLocalClientProviderListener(() -> {
      System.out.println("Connection ready >>>>>>>>>>>>>>>>");
      byte[] txBytes = HexUtil.decodeHexString("84a300818258204d77bc019ede3afd35785a801693c3e96a85c2d6ea05e8a5b2b225bc947c9e3f010182825839001c1ffaf141ebbb8e3a7072bb15f50f938b994c82de2d175f358fc942441f00edfe1b8d6a84f0d19c25a9c8829442160c0b5c758094c423441a002dc6c0825839008c5bf0f2af6f1ef08bb3f6ec702dd16e1c514b7e1d12f7549b47db9f4d943c7af0aaec774757d4745d1a2c8dd3220e6ec2c9df23f757a2f81b00000002534a872c021a00029075a100818258209518c18103cbdab9c6e60b58ecc3e2eb439fef6519bb22570f391327381900a85840da208874993a6ac5955c090f2aed7d54d2d98d47b84ed79edaf3bb7d69844c9fa9ac62a56c5d26a807b2fb264c869efaf4e6889b6c6ac7555e1b5f570c77f405f5f6");
      TxSubmissionRequest txnRequest = new TxSubmissionRequest(TxBodyType.BABBAGE, txBytes);
      localTxSubmissionClient.submitTxCallback(txnRequest);
});
```

## Tx Submission using reactive api (Experimental)

Alternatively, you can use ``submitTx`` reactive api to submit tx.

```java
TxSubmissionRequest txnRequest = new TxSubmissionRequest(txBytes);

Mono<TxResult> txResultMono = localTxSubmissionClient.submitTx(txnRequest);
txResultMono.subscribe(txResult -> {
      System.out.println("TxId: " + txResult.getTxHash());
      System.out.println("Accepted: " + txResult.isAccepted());
});
```
