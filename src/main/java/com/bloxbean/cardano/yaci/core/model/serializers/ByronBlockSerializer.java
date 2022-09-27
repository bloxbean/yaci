package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

public enum ByronBlockSerializer implements Serializer<ByronMainBlock> {
    INSTANCE;

    @Override
    public ByronMainBlock deserializeDI(DataItem di) {
        Array array = (Array) di;
        int eraValue = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        Era era = EraUtil.getEra(eraValue);
        if (era != Era.Byron)
            throw new IllegalArgumentException("Not a Byron block");

        Array mainBlkArray = (Array) array.getDataItems().get(1);
      //  String blockHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(array)));

        ByronMainBlock.ByronMainBlockBuilder blockBuilder = ByronMainBlock.builder();

        //header
        Array headerArr = (Array) mainBlkArray.getDataItems().get(0);
        //body
        Array bodyArr = (Array) mainBlkArray.getDataItems().get(1);
        //Extra
        Array extraArr = (Array) mainBlkArray.getDataItems().get(2);

        ByronBlockHead header = deserializeHeader(headerArr);
        ByronBlockBody blockBody = deserializeBlockBody(bodyArr);

        ByronMainBlock block = ByronMainBlock.builder()
                .header(header)
                .body(blockBody)
                .build();

        return block;
    }

    public ByronBlockHead deserializeHeader(Array headerArr) {
        long protocolMagic = toLong(headerArr.getDataItems().get(0));
        String prevBlockId = toHex(headerArr.getDataItems().get(1));

        //String bodyProof = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(headerArr.getDataItems().get(2))));
        String bodyProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(headerArr.getDataItems().get(2)));
        ByronBlockCons consensusData = deserializeConsensusData(headerArr.getDataItems().get(3));
        String extraData = HexUtil.encodeHexString(CborSerializationUtil.serialize(headerArr.getDataItems().get(4)));

        return new ByronBlockHead(protocolMagic, prevBlockId, bodyProof, consensusData, extraData);
    }

    private ByronBlockCons deserializeConsensusData(DataItem dataItem) {
        Array consArray = (Array) dataItem;
        List<DataItem> consDIs = consArray.getDataItems();

        //slotid
        Array epochArr = (Array)consDIs.get(0);
        long epochNo = toLong(epochArr.getDataItems().get(0));
        long slot = toLong(epochArr.getDataItems().get(1));
        Epoch slotId = new Epoch(epochNo, slot);

        String pubKey = toHex(consDIs.get(1));

        BigInteger difficulty = toBigInteger(((Array)consDIs.get(2)).getDataItems().get(0));
        String blocksig = HexUtil.encodeHexString(CborSerializationUtil.serialize(consDIs.get(3)));

        return new ByronBlockCons(slotId, pubKey, difficulty, blocksig);

    }

    private ByronBlockBody deserializeBlockBody(Array bodyArr) {
//        "txPayload" : [* [tx, [* twit]]
        Array txPayloadArr = (Array) bodyArr.getDataItems().get(0);

        List<ByronTx> txBodies = new ArrayList<>();
        for (DataItem txPayloadDI: txPayloadArr.getDataItems()) {
            if (txPayloadDI == Special.BREAK)
                continue;
            Array txnArray = (Array) ((Array) txPayloadDI).getDataItems().get(0);
            Array txnWitnessArray = (Array) ((Array) txPayloadDI).getDataItems().get(1);

            Array txInArray = (Array) txnArray.getDataItems().get(0);
            Array txOutArray = (Array) txnArray.getDataItems().get(1);
            //attributes TODO

            List<ByronTxIn> txInputs = new ArrayList<>();
            for (DataItem txIn: txInArray.getDataItems()) {
                if (txIn == Special.BREAK) continue;
                Array txInArr = (Array) txIn;

                //0th item is 0
                //txin = [0, #6.24(bytes .cbor ([txid, u32]))] / [u8 .ne 0, encoded-cbor]
                Array txInDI_1 = (Array)CborSerializationUtil.deserializeOne(((ByteString)txInArr.getDataItems().get(1)).getBytes());
                String txInHash = toHex(txInDI_1.getDataItems().get(0));
                int txIndex = toInt(txInDI_1.getDataItems().get(1));

                txInputs.add(new ByronTxIn(txInHash, txIndex));
            }

            List<ByronTxOut> txOutputs = new ArrayList<>();
            for (DataItem txOut: txOutArray.getDataItems()) {
                if (txOut == Special.BREAK) continue;

                Array txOutArr = (Array) txOut;
                String address = toHex(((Array)(txOutArr.getDataItems().get(0))).getDataItems().get(0));
//                BigInteger someValue = toBigInteger(((Array)(txOutArr.getDataItems().get(0))).getDataItems().get(1));
                BigInteger value = toBigInteger(txOutArr.getDataItems().get(1));

                ByronTxOut txOutput = ByronTxOut.builder()
                        .address(address)
                        .amount(value).build();

                txOutputs.add(txOutput);
            }

            ByronTx txBody = ByronTx.builder()
                    .inputs(txInputs)
                    .outputs(txOutputs)
                    .build();
            txBodies.add(txBody);
        }

        ByronBlockBody blockBody = new ByronBlockBody(txBodies);
        return blockBody;
    }

    public static void main(String[] args) {
        String blockHex = "820183851a2d964a095820044ecc574d25492aff1149617810533c402385cd202b321b778356969d31640c84830258209abe0fbe42852998219f832552b5213cd635e8024604f7574f7f636046d7a625582026864ddfc05731b626de6ed6397062ba643778a93db37ba4a8e20660aa0249ff82035820d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6c5820afc0da64183bf2664f3d4eec7238d524ba607faeeab24fc100eb861dba69971b58204e66280cd94d591072349bec0a3090a53aa945562efb6d08d56e53654b0e4098848218bd1927e458401bc97a2fe02c297880ce8ecfd997fe4c1ec09ee10feeee9f686760166b05281d6283468ffd93becb0c956ccddd642df9b1244c915911185fa49355f6f22bfab9811a003e6a8e820282840058401bc97a2fe02c297880ce8ecfd997fe4c1ec09ee10feeee9f686760166b05281d6283468ffd93becb0c956ccddd642df9b1244c915911185fa49355f6f22bfab9584061261a95b7613ee6bf2067dad77b70349729b0c50d57bc1cf30de0db4a1e73a885d0054af7c23fc6c37919dba41c602a57e2d0f9329a7954b867338d6fb2c9455840e03e62f083df5576360e60a32e22bbb07b3c8df4fcab8079f1d6f61af3954d242ba8a06516c395939f24096f3df14e103a7d9c2b80a68a9363cf1f27c7a4e30758405e828bfa6dfd2d3726748af4c260664febf8bff81281deb64c800775f940b75a63aab82fc9fad5dc04c1610bf2e1cd4281a20929cf2754294524cc779fc5b60d8483010000826a63617264616e6f2d736c02a058204ba92aa320c60acc9ad7b9a64f2eda55c4d2ec28e604faf186708b4f0c4e8edf849f82839f8200d8185824825820ca7bb064c3add461f31871b28dacab0fd5c8b984445865fbf13f75b16e6b05b0018200d8185824825820e5930a47eb20da3c7724d8d7889871e90c9f97d441148d7db7deb294a50ece8200ff9f8282d818584283581c6d64881404e50a1b61b8228509e6c8c2ecdd5cb5c18dc4dbd3a31371a101581e581cd2ac21d265a800d952caee329a21bafd2d2cc69c947c230c94080fb1001a94b0e2651b00000010dc7d0db98282d818584283581cdb644479dac5fd9b981895e0cdb57ba95e52622b7bf7969e3b075dbca101581e581cea790bf3e4a99e368509cd7681c0fc8f2c537049f398dda7a986dbe8001a1fbfc93a1b00000002540be400ffa0828200d8185885825840084429d6c46004fe0e4cbf8406666c8102b716b1795b234e52dabd47e8d0c9414ceaacd7c13f22cde3118c6df0bfd8841dbfee9bdede7836567506e500894e67584071eee54b178ebdb9739c041acf803b40d90cfd3fd1ea316ae14a80cbe771e4f7a827931d77afb7cc9045f242bea86e4da4f038e84ea380c2807a6cf3de13a8068200d818588582584036bc3bfab8944cd022b776a208349d039a02b8da6e848712db73ac7b45a04d9131d5d00dabb17041fa40d8425c6f977711fd4e04048a05bf895ee8ade6a0171f5840d9f25394a377b2019ed5b42fc4fc2da1530536a6a9adaef2056985df85175df35ce578ca6e4e295e6f8f46aef32a4e9e22612675250e952eadce36e1a1d80c0882839f8200d8185824825820cf33e22c25119431b19ed1d72e4d42709ff0828e94844b2cf2b771b2ceabe26a00ff9f8282d818582183581ca4b9e9335ca76da5c6253410d11f8392ca235fdaa80496064264603ea0001a934def6c1a1720ff2e8282d818584283581c3a8bf0b27fa6bf29f2a049951a74961bfca67b3fc733f08ab6ff8143a101581e581c9b17098ec5544a26e85de8a96856b094427ff4e12b1e6ce5f6c3d4e5001a1ccaa7811b0000007df0d6e2b0ffa0818200d8185885825840150f5212b8f14abec0eefdc11cb9fe9945094147a1f4c5021557c7bf57ae3531a39a24c209cbecc8beb992ff9efe3c5a185625968e2c3ef67b9636aaf298c3205840f53208481db698a3d4348958b116bb6daeb0f6a8334721e7d28c47cb2ca5c3b213bacbe7bd0d9e3c45ceaad7ed660a46d8ffc75b37d9ae69e44918770167cd0cff8203d90102809fff82809fff81a0";
        ByronMainBlock byronBlock = ByronBlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(blockHex));

        System.out.println(JsonUtil.getPrettyJson(byronBlock));
    }
}
