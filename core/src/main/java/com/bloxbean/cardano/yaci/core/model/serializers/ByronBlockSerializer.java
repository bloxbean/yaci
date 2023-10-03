package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.*;
import com.bloxbean.cardano.yaci.core.model.byron.payload.*;
import com.bloxbean.cardano.yaci.core.model.byron.signature.BlockSignature;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import lombok.SneakyThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.model.byron.ByronAddressTypes.*;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

public enum ByronBlockSerializer implements Serializer<ByronMainBlock> {
    INSTANCE;

    @Override
    public ByronMainBlock deserialize(byte[] bytes) {
        DataItem dataItem = CborSerializationUtil.deserializeOne(bytes);
        return deserializeByronBlock(dataItem, bytes);
    }

    private ByronMainBlock deserializeByronBlock(DataItem di, byte[] blockBytes) {
        Array array = (Array) di;
        int eraValue = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
        Era era = EraUtil.getEra(eraValue);
        if (era != Era.Byron) {
            throw new IllegalArgumentException("Not a Byron block");
        }

        Array mainBlkArray = (Array) array.getDataItems().get(1);

        //header
        Array headerArr = (Array) mainBlkArray.getDataItems().get(0);
        //body
        Array bodyArr = (Array) mainBlkArray.getDataItems().get(1);
        //TODO Extra
        Array extraArr = (Array) mainBlkArray.getDataItems().get(2);

        ByronBlockHead header = deserializeHeader(headerArr);
        ByronBlockBody body = deserializeBlockBody(bodyArr);

        String cbor = YaciConfig.INSTANCE.isReturnBlockCbor()? HexUtil.encodeHexString(blockBytes) : null;

        return ByronMainBlock.builder()
                .header(header)
                .body(body)
                .cbor(cbor)
                .build();
    }

    public ByronBlockHead deserializeHeader(Array headerArr) {
        long protocolMagic = toLong(headerArr.getDataItems().get(0));
        String prevBlockId = toHex(headerArr.getDataItems().get(1));

        //Calculate block hash
        Array blockHashArray = new Array();
        // hash expects to have a prefix for the type of block
        blockHashArray.add(new UnsignedInteger(1)); //For main block 1
        blockHashArray.add(headerArr);
        String blockHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(CborSerializationUtil.serialize(blockHashArray)));

        ByronBlockProof bodyProof = deserializeBodyProof(headerArr.getDataItems().get(2));
        ByronBlockCons consensusData = deserializeConsensusData(headerArr.getDataItems().get(3));
        ByronBlockExtraData<String> extraData = deserializeExtraData(headerArr.getDataItems().get(4));

        return new ByronBlockHead(protocolMagic, prevBlockId, bodyProof, consensusData,
                extraData, blockHash);
    }

    private ByronBlockProof deserializeBodyProof(DataItem dataItem) {
        Array proofsArray = (Array) dataItem;
        List<DataItem> proofDIs = proofsArray.getDataItems();

        ByronTxProof txProof = deserializeTxProof(proofDIs.get(0));
        ByronSscProof sscProof = deserializeSscProof(proofDIs.get(1));
        String dlgProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(proofDIs.get(2)));
        String updProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(proofDIs.get(3)));

        return ByronBlockProof.builder()
                .txProof(txProof)
                .sscProof(sscProof)
                .dlgProof(dlgProof)
                .updProof(updProof)
                .build();
    }

    private ByronTxProof deserializeTxProof(DataItem dataItem) {
        List<DataItem> txProofDIs = ((Array) dataItem).getDataItems();
        long txpNumber = ((UnsignedInteger) txProofDIs.get(0)).getValue().longValue();
        String txpRoot = HexUtil.encodeHexString(CborSerializationUtil.serialize(txProofDIs.get(1)));
        String txpWitnessesHash = HexUtil.encodeHexString(
                CborSerializationUtil.serialize(txProofDIs.get(2)));

        return ByronTxProof.builder()
                .txpNumber(txpNumber)
                .txpRoot(txpRoot)
                .txpWitnessesHash(txpWitnessesHash)
                .build();
    }

    private ByronSscProof deserializeSscProof(DataItem dataItem) {
        Array proofsArray = (Array) dataItem;
        List<DataItem> proofDIs = proofsArray.getDataItems();

        String payloadProof = null;
        String certificatesProof;

        if (proofDIs.size() == 3) {
            payloadProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(proofDIs.get(1)));
            certificatesProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(proofDIs.get(2)));
        } else {
            certificatesProof = HexUtil.encodeHexString(CborSerializationUtil.serialize(proofDIs.get(1)));
        }

        return new ByronSscProof(payloadProof, certificatesProof);
    }

    @SneakyThrows
    private ByronBlockCons deserializeConsensusData(DataItem dataItem) {
        Array consArray = (Array) dataItem;
        List<DataItem> consDIs = consArray.getDataItems();

        //slotid
        Array epochArr = (Array) consDIs.get(0);
        long epochNo = toLong(epochArr.getDataItems().get(0));
        long slot = toLong(epochArr.getDataItems().get(1));
        Epoch slotId = new Epoch(epochNo, slot);

        String pubKey = toHex(consDIs.get(1));

        BigInteger difficulty = toBigInteger(((Array) consDIs.get(2)).getDataItems().get(0));
        BlockSignature blockSig = BlockSignature.deserialize(consDIs.get(3));
        return new ByronBlockCons(slotId, pubKey, difficulty, blockSig);

    }

    private ByronBlockExtraData<String> deserializeExtraData(DataItem dataItem) {
        Array consArray = (Array) dataItem;
        List<DataItem> extraDataDIs = consArray.getDataItems();

        Array blockVersionArray = (Array) extraDataDIs.get(0);
        BlockVersion blockVersion = new BlockVersion(
                toShort(blockVersionArray.getDataItems().get(0)),
                toShort(blockVersionArray.getDataItems().get(1)),
                toByte(blockVersionArray.getDataItems().get(2))
        );

        Array softwareVersionArray = (Array) extraDataDIs.get(1);
        SoftwareVersion softwareVersion = new SoftwareVersion(
                HexUtil.encodeHexString(
                        CborSerializationUtil.serialize(softwareVersionArray.getDataItems().get(0))),
                toLong(softwareVersionArray.getDataItems().get(1))
        );

        String attributes = HexUtil.encodeHexString(
                CborSerializationUtil.serialize(extraDataDIs.get(2)));
        String extraProof = HexUtil.encodeHexString(
                CborSerializationUtil.serialize(extraDataDIs.get(3)));

        return new ByronBlockExtraData<>(blockVersion, softwareVersion, attributes, extraProof);
    }

    private ByronBlockBody deserializeBlockBody(Array bodyArr) {
        List<DataItem> bodyDIs = bodyArr.getDataItems();
        List<ByronTxPayload> txPayload = deserializeTxPayload(bodyDIs.get(0));
        ByronSscPayload sscPayload = deserializeSscPayload(bodyDIs.get(1));
        List<ByronDlgPayload> dlgPayload = deserializeDlgPayload(bodyDIs.get(2));
        ByronUpdatePayload updPayload = deserializeUpdPayload(bodyDIs.get(3));

        return ByronBlockBody.builder()
                .txPayload(txPayload)
                .sscPayload(sscPayload)
                .dlgPayload(dlgPayload)
                .updPayload(updPayload)
                .build();
    }

    private List<ByronTxPayload> deserializeTxPayload(DataItem dataItem) {
//        "txPayload" : [* [tx, [* twit]]
        Array txPayloadArr = (Array) dataItem;

        return txPayloadArr.getDataItems().stream()
                .filter(txPayloadDI -> txPayloadDI.getMajorType() != MajorType.SPECIAL)
                .map(this::deserializeTransaction)
                .collect(Collectors.toList());
    }

    private ByronTxPayload deserializeTransaction(DataItem txPayloadDI) {
        Array txnArray = (Array) ((Array) txPayloadDI).getDataItems().get(0);
        Array txnWitnessArray = (Array) ((Array) txPayloadDI).getDataItems().get(1);

        Array txInArray = (Array) txnArray.getDataItems().get(0);
        Array txOutArray = (Array) txnArray.getDataItems().get(1);
        //attributes TODO

        List<ByronTxIn> txInputs = new ArrayList<>(0);
        for (DataItem txIn : txInArray.getDataItems()) {
            if (txIn == Special.BREAK) {
                continue;
            }
            Array txInArr = (Array) txIn;

            //0th item is 0
            //txin = [0, #6.24(bytes .cbor ([txid, u32]))] / [u8 .ne 0, encoded-cbor]
            Array actualTxInArr = (Array) CborSerializationUtil
                    .deserializeOne(
                            (CborSerializationUtil.toBytes(txInArr.getDataItems().get(1)))
                    );
            String txInHash = toHex(actualTxInArr.getDataItems().get(0));
            int txIndex = toInt(actualTxInArr.getDataItems().get(1));

            txInputs.add(new ByronTxIn(txInHash, txIndex));
        }

        List<ByronTxOut> txOutputs = new ArrayList<>(0);
        for (DataItem txOut : txOutArray.getDataItems()) {
            if (txOut == Special.BREAK) {
                continue;
            }

            Array txOutArr = (Array) txOut;
            Array addressArray = (Array) txOutArr.getDataItems().get(0);
            ByronAddress address = deserializeAddress(addressArray);
            BigInteger value = toBigInteger(txOutArr.getDataItems().get(1));

            ByronTxOut txOutput = ByronTxOut.builder()
                    .address(address)
                    .amount(value).build();

            txOutputs.add(txOutput);
        }

        String txHash = TxUtil.calculateTxHash(CborSerializationUtil.serialize(txnArray, false));
        ByronTx txBody = ByronTx.builder()
                .inputs(txInputs)
                .outputs(txOutputs)
                .txHash(txHash)
                .build();
        List<ByronTxWitnesses> witnesses = deserializeTxWitnesses(txnWitnessArray);

        return new ByronTxPayload(txBody, witnesses);
    }

    private ByronAddress deserializeAddress(Array addressArray) {
        String b58Address = Base58.encode(CborSerializationUtil.serialize(addressArray));
        ByteString addressCbor = (ByteString) addressArray.getDataItems().get(0);
        List<DataItem> addressDIs = ((Array) CborSerializationUtil.deserializeOne(
                addressCbor.getBytes())).getDataItems();

        String addressId = HexUtil.encodeHexString(((ByteString) addressDIs.get(0)).getBytes());
        ByronAddressAttr addressAttr = deserializeAddressAttr(addressDIs.get(1));
        String addressType = deserializeAddressType(
                ((UnsignedInteger) addressDIs.get(2)).getValue().intValue());

        return new ByronAddress(
                b58Address,
                addressId,
                addressAttr,
                addressType
        );
    }

    private static ByronAddressAttr deserializeAddressAttr(DataItem dataItem) {
        co.nstant.in.cbor.model.Map addressAttributes = (co.nstant.in.cbor.model.Map) dataItem;
        DataItem stakeDistribution = addressAttributes.get(new UnsignedInteger(2));
        DataItem pkDerivationPath = addressAttributes.get(new UnsignedInteger(1));

        ByronAddressAttr.ByronAddressAttrBuilder builder = ByronAddressAttr.builder();
        if (stakeDistribution != null) {
            builder.stakeDistribution(
                    HexUtil.encodeHexString(CborSerializationUtil.serialize(stakeDistribution)));
        }

        if (pkDerivationPath != null) {
            builder.pkDerivationPath(
                    HexUtil.encodeHexString(CborSerializationUtil.serialize(pkDerivationPath)));
        }

        return builder.build();
    }

    private static String deserializeAddressType(int addrType) {
        switch (addrType) {
            case 0:
                return PUB_KEY;
            case 1:
                return SCRIPT;
            case 2:
                return REDEEM;
            default:
                return UNKNOWN;
        }
    }

    private List<ByronTxWitnesses> deserializeTxWitnesses(Array txnWitnessArray) {
        List<DataItem> txWitnesses = txnWitnessArray.getDataItems();

        return txWitnesses.stream().map(txWitness -> {
            List<DataItem> txWitDIs = ((Array) txWitness).getDataItems();
            int id = ((UnsignedInteger) txWitDIs.get(0)).getValue().intValue();
            switch (id) {
                case 0:
                    return deserializePkWitness(txWitDIs.get(1));
                case 1:
                    return deserializeScriptWitness(txWitDIs.get(1));
                case 2:
                    return deserializeRedeemWitness(txWitDIs.get(1));
                default:
                    return deserializeUnknownWitness(txWitDIs.get(1));
            }
        }).collect(Collectors.toList());
    }

    private static ByronPkWitness deserializePkWitness(DataItem dataItem) {
        DataItem pkWitnessDI = CborSerializationUtil.deserializeOne(((ByteString) dataItem).getBytes());
        List<DataItem> actualPkWitnessDIs = ((Array) pkWitnessDI).getDataItems();

        String publicKey = HexUtil.encodeHexString(((ByteString) actualPkWitnessDIs.get(0)).getBytes());
        String signature = HexUtil.encodeHexString(((ByteString) actualPkWitnessDIs.get(1)).getBytes());
        return new ByronPkWitness(publicKey, signature);
    }

    private static ByronScriptWitness deserializeScriptWitness(DataItem dataItem) {
        DataItem scriptWitnessDI = CborSerializationUtil
                .deserializeOne(CborSerializationUtil.toBytes(dataItem));

        List<DataItem> actualScriptWitnessDIs = ((Array) scriptWitnessDI).getDataItems();

        ByronScript validator = deserializeScript(actualScriptWitnessDIs.get(0));
        ByronScript redeemer = deserializeScript(actualScriptWitnessDIs.get(1));
        return new ByronScriptWitness(validator, redeemer);
    }

    private static ByronScript deserializeScript(DataItem dataItem) {
        List<DataItem> scriptDIs = ((Array) dataItem).getDataItems();

        long scriptVersion = ((UnsignedInteger) scriptDIs.get(0)).getValue().longValue();
        String script = HexUtil.encodeHexString(CborSerializationUtil.toBytes(scriptDIs.get(1)));

        return new ByronScript(scriptVersion, script);
    }

    private static ByronRedeemWitness deserializeRedeemWitness(DataItem dataItem) {
        DataItem redeemWitnessDI = CborSerializationUtil
                .deserializeOne(CborSerializationUtil.toBytes(dataItem));
        List<DataItem> actualRedeemWitnessDIs = ((Array) redeemWitnessDI).getDataItems();

        String redeemPublicKey = HexUtil.encodeHexString(
                CborSerializationUtil.toBytes(actualRedeemWitnessDIs.get(0)));
        String redeemSignature = HexUtil.encodeHexString(
                CborSerializationUtil.toBytes(actualRedeemWitnessDIs.get(1)));
        return new ByronRedeemWitness(redeemPublicKey, redeemSignature);
    }

    private static ByronUnknownWitness deserializeUnknownWitness(DataItem dataItem) {
        String data = HexUtil.encodeHexString(CborSerializationUtil.toBytes(dataItem));
        return new ByronUnknownWitness(data);
    }

    private static ByronSscPayload deserializeSscPayload(DataItem dataItem) {
        List<DataItem> sscDIs = ((Array) dataItem).getDataItems();
        int id = ((UnsignedInteger) sscDIs.get(0)).getValue().intValue();
        switch (id) {
            case 0:
                return deserializeCommitmentsPayload(sscDIs);
            case 1:
                return deserializeOpeningsPayload(sscDIs);
            case 2:
                return deserializeSharesPayload(sscDIs);
            default:
                return deserializeCertificatesPayload(sscDIs);
        }
    }

    private static ByronCommitmentsPayload deserializeCommitmentsPayload(List<DataItem> dataItems) {
        List<DataItem> commitments = ((Array) dataItems.get(1)).getDataItems();
        List<ByronSignedCommitment> sscCommitments = commitments.stream()
                .map(ByronBlockSerializer::deserializeSscComm)
                .collect(Collectors.toList());
        List<ByronSscCert> sscCerts = deserializeSscCerts(dataItems.get(2));
        return new ByronCommitmentsPayload(sscCommitments, sscCerts);
    }

    private static ByronSignedCommitment deserializeSscComm(DataItem dataItem) {
        List<DataItem> dataItems = ((Array) dataItem).getDataItems();
        String publicKey = HexUtil.encodeHexString(CborSerializationUtil.toBytes(dataItems.get(0)));
        ByronCommitment commitment = deserializeCommitment(dataItems.get(1));
        String signature = HexUtil.encodeHexString(CborSerializationUtil.toBytes(dataItems.get(2)));

        return new ByronSignedCommitment(publicKey, commitment, signature);
    }

    private static ByronCommitment deserializeCommitment(DataItem dataItem) {
        List<DataItem> commitment = ((Array) dataItem).getDataItems();
        java.util.Map<String, String> map = deserializeShares(commitment.get(0));
        ByronSecretProof vssProof = deserializeSecretProof(commitment.get(1));
        return new ByronCommitment(map, vssProof);
    }

    private static java.util.Map<String, String> deserializeShares(DataItem dataItem) {
        co.nstant.in.cbor.model.Map shares = (co.nstant.in.cbor.model.Map) dataItem;
        Collection<DataItem> keys = shares.getKeys();
        return keys.stream().collect(Collectors.toMap(
                key -> HexUtil.encodeHexString(CborSerializationUtil.toBytes(key)),
                key -> HexUtil.encodeHexString(getEncShare(shares.get(key)).getBytes())
        ));
    }

    private static ByteString getEncShare(DataItem dataItem) {
        if (dataItem.getMajorType() == MajorType.BYTE_STRING) {
            return (ByteString) dataItem;
        }

        return (ByteString) ((Array) dataItem).getDataItems().get(0);
    }

    private static ByronSecretProof deserializeSecretProof(DataItem dataItem) {
        List<DataItem> dataItems = ((Array) dataItem).getDataItems();
        String extraGen = HexUtil.encodeHexString(CborSerializationUtil.toBytes(dataItems.get(0)));
        String proof = HexUtil.encodeHexString(CborSerializationUtil.toBytes(dataItems.get(1)));
        String parallelProofs = HexUtil.encodeHexString(
                CborSerializationUtil.toBytes(dataItems.get(2)));
        List<String> commitments = ((Array) dataItems.get(3)).getDataItems().stream()
                .filter(commitment -> commitment.getMajorType() != MajorType.SPECIAL)
                .map(commitment -> HexUtil.encodeHexString(CborSerializationUtil.toBytes(commitment)))
                .collect(Collectors.toList());

        return new ByronSecretProof(extraGen, proof, parallelProofs, commitments);
    }

    private static ByronOpeningsPayload deserializeOpeningsPayload(List<DataItem> dataItems) {
        co.nstant.in.cbor.model.Map openings = (co.nstant.in.cbor.model.Map) dataItems.get(1);
        Collection<DataItem> stakeholderIds = openings.getKeys();
        java.util.Map<String, String> sscOpens = stakeholderIds.stream().collect(Collectors.toMap(
                key -> HexUtil.encodeHexString(CborSerializationUtil.toBytes(key)),
                key -> HexUtil.encodeHexString(CborSerializationUtil.toBytes(openings.get(key)))
        ));
        List<ByronSscCert> sscCerts = deserializeSscCerts(dataItems.get(2));

        return new ByronOpeningsPayload(sscOpens, sscCerts);
    }

    private static ByronSharesPayload deserializeSharesPayload(List<DataItem> dataItems) {
        return new ByronSharesPayload();
    }

    private static ByronCertificatesPayload deserializeCertificatesPayload(List<DataItem> dataItems) {
        return new ByronCertificatesPayload(deserializeSscCerts(dataItems.get(1)));
    }

    private static List<ByronSscCert> deserializeSscCerts(DataItem dataItem) {
        List<DataItem> certs = ((Array) dataItem).getDataItems();

        return certs.stream().map(cert -> {
            List<DataItem> dataItems = ((Array) cert).getDataItems();
            String vssPublicKey = HexUtil.encodeHexString(
                    CborSerializationUtil.toBytes(dataItems.get(0)));
            long expiryEpoch = ((UnsignedInteger) dataItems.get(1)).getValue().longValue();
            String signature = HexUtil.encodeHexString(CborSerializationUtil.toBytes(dataItems.get(2)));
            String publicKey = HexUtil.encodeHexString((CborSerializationUtil.toBytes(dataItems.get(3))));

            return new ByronSscCert(vssPublicKey, expiryEpoch, signature, publicKey);
        }).collect(Collectors.toList());
    }

    private List<ByronDlgPayload> deserializeDlgPayload(DataItem dataItem) {
        return ((Array) dataItem).getDataItems()
                .stream()
                .filter(item -> !item.getOuterTaggable().equals(Special.BREAK))
                .map(ByronDlgPayload::deserialize)
                .collect(Collectors.toList());
    }

    private ByronUpdatePayload deserializeUpdPayload(DataItem dataItem) {
        List<DataItem> dataItems = ((Array) (dataItem)).getDataItems();
        return ByronUpdatePayload.builder()
                .proposal(deserializeByronUpdateProposal(dataItems.get(0)))
                .votes(deserializeByronUpdateVote(dataItems.get(1)))
                .build();
    }

    private ByronUpdateProposal deserializeByronUpdateProposal(DataItem dataItem) {
        Optional<ByronUpdateProposal> optional =  ((Array) dataItem).getDataItems()
                .stream()
                .filter(item -> !item.getOuterTaggable().equals(Special.BREAK))
                .map(dataItems -> {
                    List<DataItem> items = ((Array) dataItems).getDataItems();

                    return ByronUpdateProposal.builder()
                            .blockVersion(deserializerBlockVersion(items.get(0)))
                            .blockVersionMod(deserializerByronBlockVersionMod(items.get(1)))
                            .softwareVersion(deserializerSoftwareVersion(items.get(2)))
                            .data(deserializerByronData(items.get(3)))
                            .attributes("")
                            .from(HexUtil.encodeHexString(CborSerializationUtil.toBytes(items.get(5))))
                            .signature(HexUtil.encodeHexString(CborSerializationUtil.toBytes(items.get(5))))
                            .build();
                })
                .findFirst();

        if(optional.isEmpty()){
            return ByronUpdateProposal.builder()
                    .build();
        }

        return optional.get();
    }

    private List<ByronUpdateVote> deserializeByronUpdateVote(DataItem dataItems) {
        return ((Array) dataItems).getDataItems()
                .stream()
                .filter(item -> !item.getOuterTaggable().equals(Special.BREAK))
                .map(items -> {
                    List<DataItem> vote = ((Array) items).getDataItems();
                    return ByronUpdateVote.builder()
                            .voter(HexUtil.encodeHexString(CborSerializationUtil.toBytes(vote.get(0))))
                            .proposalId(HexUtil.encodeHexString(CborSerializationUtil.toBytes(vote.get(1))))
                            .vote(Boolean.valueOf(
                                    ((SimpleValue) vote.get(2))
                                            .getSimpleValueType()
                                            .name().
                                            toLowerCase()))
                            .signature(HexUtil.encodeHexString(CborSerializationUtil.toBytes(vote.get(3))))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private ByronBlockVersion deserializerBlockVersion(DataItem dataItems) {

        List<DataItem> items = ((Array) dataItems).getDataItems();

        return ByronBlockVersion.builder()
                .major(((UnsignedInteger) items.get(0)).getValue().longValue())
                .minor(((UnsignedInteger) items.get(1)).getValue().longValue())
                .alt(((UnsignedInteger) items.get(2)).getValue().longValue())
                .build();
    }

    private ByronBlockVersionMod deserializerByronBlockVersionMod(DataItem dataItems) {
        List<DataItem> items = ((Array) dataItems).getDataItems();
        return ByronBlockVersionMod.builder()
                .scriptVersion(toLongFromArray(items.get(0)))
                .slotDuration(toBigIntegerFromArray(items.get(1)))
                .maxBlockSize(toBigIntegerFromArray(items.get(2)))
                .maxHeaderSize(toBigIntegerFromArray(items.get(3)))
                .maxTxSize(toBigIntegerFromArray(items.get(4)))
                .maxProposalSize(toBigIntegerFromArray(items.get(5)))
                .mpcThd(toBigDecimalFromArray(items.get(6)))
                .heavyDelThd(toBigDecimalFromArray(items.get(7)))
                .updateVoteThd(toBigDecimalFromArray(items.get(8)))
                .updateProposalThd(toBigDecimalFromArray(items.get(9)))
                .updateImplicit(toBigIntegerFromArray(items.get(10)))
                .softForkRule(deserializerSoftForkRule(items.get(11)))
                .txFeePolicy(deserializerTxFeePolicy(items.get(12)))
                .unlockStakeEpoch(toLongFromArray(items.get(13)))
                .build();
    }

    private java.util.Map<BigInteger, Object> deserializerTxFeePolicy(DataItem items) {
        return Collections.emptyMap();
    }

    private List<BigInteger> deserializerSoftForkRule(DataItem items) {
        List<DataItem> dataItems = ((Array) items).getDataItems();
        if (dataItems == null || dataItems.isEmpty()) {
            return Collections.emptyList();
        }

        return List.of(
                toBigInteger(dataItems.get(0)),
                toBigInteger(dataItems.get(1)),
                toBigInteger(dataItems.get(2))
        );
    }

    private Long toLongFromArray(DataItem items) {
        List<DataItem> dataItems = ((Array) items).getDataItems();

        if (dataItems == null || dataItems.isEmpty()) {
            return null;
        }
        return ((UnsignedInteger) dataItems.
                get(BigInteger.ZERO.intValue()))
                .getValue().longValue();
    }

    private BigInteger toBigIntegerFromArray(DataItem items) {
        List<DataItem> dataItems = ((Array) items).getDataItems();

        if (dataItems == null || dataItems.isEmpty()) {
            return null;
        }
        return ((UnsignedInteger) dataItems.
                get(BigInteger.ZERO.intValue()))
                .getValue();
    }

    private BigDecimal toBigDecimalFromArray(DataItem items) {
        List<DataItem> dataItems = ((Array) items).getDataItems();

        if (dataItems == null || dataItems.isEmpty()) {
            return null;
        }
        return new BigDecimal(((UnsignedInteger) dataItems.
                get(BigInteger.ZERO.intValue()))
                .getValue());
    }

    private SoftwareVersion deserializerSoftwareVersion(DataItem items) {
        List<DataItem> dataItems = ((Array) items).getDataItems();

        if (dataItems == null || dataItems.isEmpty()) {
            return SoftwareVersion
                    .builder()
                    .build();
        }

        return SoftwareVersion
                .builder()
                .appName(toUnicodeString(dataItems.get(0)))
                .number(toBigInteger(dataItems.get(1)).longValue())
                .build();
    }

    private java.util.Map<String, ByronUpdateData> deserializerByronData(DataItem items) {
        co.nstant.in.cbor.model.Map map = (co.nstant.in.cbor.model.Map) items;

        return map.getKeys()
                .stream()
                .collect(
                        Collectors.toMap(
                                CborSerializationUtil::toUnicodeString,
                                key -> {
                                    List<DataItem> hash = ((Array)map.get(key)).getDataItems();

                                    return  ByronUpdateData.builder()
                                            .appDiffHash(CborSerializationUtil.toHex(hash.get(0)))
                                            .pkgHash(CborSerializationUtil.toHex(hash.get(1)))
                                            .updaterHash(CborSerializationUtil.toHex(hash.get(2)))
                                            .metadataHash(CborSerializationUtil.toHex(hash.get(3)))
                                            .build();
                                }
                        ));
    }


    public static void main(String[] args) {
        String blockHex = "820183851a2d964a095820044ecc574d25492aff1149617810533c402385cd202b321b778356969d31640c84830258209abe0fbe42852998219f832552b5213cd635e8024604f7574f7f636046d7a625582026864ddfc05731b626de6ed6397062ba643778a93db37ba4a8e20660aa0249ff82035820d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6c5820afc0da64183bf2664f3d4eec7238d524ba607faeeab24fc100eb861dba69971b58204e66280cd94d591072349bec0a3090a53aa945562efb6d08d56e53654b0e4098848218bd1927e458401bc97a2fe02c297880ce8ecfd997fe4c1ec09ee10feeee9f686760166b05281d6283468ffd93becb0c956ccddd642df9b1244c915911185fa49355f6f22bfab9811a003e6a8e820282840058401bc97a2fe02c297880ce8ecfd997fe4c1ec09ee10feeee9f686760166b05281d6283468ffd93becb0c956ccddd642df9b1244c915911185fa49355f6f22bfab9584061261a95b7613ee6bf2067dad77b70349729b0c50d57bc1cf30de0db4a1e73a885d0054af7c23fc6c37919dba41c602a57e2d0f9329a7954b867338d6fb2c9455840e03e62f083df5576360e60a32e22bbb07b3c8df4fcab8079f1d6f61af3954d242ba8a06516c395939f24096f3df14e103a7d9c2b80a68a9363cf1f27c7a4e30758405e828bfa6dfd2d3726748af4c260664febf8bff81281deb64c800775f940b75a63aab82fc9fad5dc04c1610bf2e1cd4281a20929cf2754294524cc779fc5b60d8483010000826a63617264616e6f2d736c02a058204ba92aa320c60acc9ad7b9a64f2eda55c4d2ec28e604faf186708b4f0c4e8edf849f82839f8200d8185824825820ca7bb064c3add461f31871b28dacab0fd5c8b984445865fbf13f75b16e6b05b0018200d8185824825820e5930a47eb20da3c7724d8d7889871e90c9f97d441148d7db7deb294a50ece8200ff9f8282d818584283581c6d64881404e50a1b61b8228509e6c8c2ecdd5cb5c18dc4dbd3a31371a101581e581cd2ac21d265a800d952caee329a21bafd2d2cc69c947c230c94080fb1001a94b0e2651b00000010dc7d0db98282d818584283581cdb644479dac5fd9b981895e0cdb57ba95e52622b7bf7969e3b075dbca101581e581cea790bf3e4a99e368509cd7681c0fc8f2c537049f398dda7a986dbe8001a1fbfc93a1b00000002540be400ffa0828200d8185885825840084429d6c46004fe0e4cbf8406666c8102b716b1795b234e52dabd47e8d0c9414ceaacd7c13f22cde3118c6df0bfd8841dbfee9bdede7836567506e500894e67584071eee54b178ebdb9739c041acf803b40d90cfd3fd1ea316ae14a80cbe771e4f7a827931d77afb7cc9045f242bea86e4da4f038e84ea380c2807a6cf3de13a8068200d818588582584036bc3bfab8944cd022b776a208349d039a02b8da6e848712db73ac7b45a04d9131d5d00dabb17041fa40d8425c6f977711fd4e04048a05bf895ee8ade6a0171f5840d9f25394a377b2019ed5b42fc4fc2da1530536a6a9adaef2056985df85175df35ce578ca6e4e295e6f8f46aef32a4e9e22612675250e952eadce36e1a1d80c0882839f8200d8185824825820cf33e22c25119431b19ed1d72e4d42709ff0828e94844b2cf2b771b2ceabe26a00ff9f8282d818582183581ca4b9e9335ca76da5c6253410d11f8392ca235fdaa80496064264603ea0001a934def6c1a1720ff2e8282d818584283581c3a8bf0b27fa6bf29f2a049951a74961bfca67b3fc733f08ab6ff8143a101581e581c9b17098ec5544a26e85de8a96856b094427ff4e12b1e6ce5f6c3d4e5001a1ccaa7811b0000007df0d6e2b0ffa0818200d8185885825840150f5212b8f14abec0eefdc11cb9fe9945094147a1f4c5021557c7bf57ae3531a39a24c209cbecc8beb992ff9efe3c5a185625968e2c3ef67b9636aaf298c3205840f53208481db698a3d4348958b116bb6daeb0f6a8334721e7d28c47cb2ca5c3b213bacbe7bd0d9e3c45ceaad7ed660a46d8ffc75b37d9ae69e44918770167cd0cff8203d90102809fff82809fff81a0";
        ByronMainBlock byronBlock = ByronBlockSerializer.INSTANCE.deserialize(HexUtil.decodeHexString(blockHex));

        System.out.println(JsonUtil.getPrettyJson(byronBlock));
    }
}
