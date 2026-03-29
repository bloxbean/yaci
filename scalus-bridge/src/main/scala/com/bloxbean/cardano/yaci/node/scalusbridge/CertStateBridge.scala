package com.bloxbean.cardano.yaci.node.scalusbridge

import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider.DRepDelegation
import scalus.cardano.ledger.{Transaction as ScalusTx, *}
import scalus.uplc.builtin.ByteString

import scala.collection.immutable.IndexedSeq
import scala.collection.mutable

/**
 * Transaction-scoped builder that constructs Scalus CertState from LedgerStateProvider.
 *
 * For each transaction, extracts all credential hashes from withdrawals and certificates,
 * queries the provider for only those credentials, and builds DelegationState.
 */
object CertStateBridge:

  /**
   * Build CertState and totalDeposited for a transaction.
   *
   * @param provider LedgerStateProvider to query account state (may be null)
   * @param scalusTx parsed Scalus Transaction
   * @return (CertState, totalDeposited as Coin)
   */
  def build(provider: LedgerStateProvider, scalusTx: ScalusTx): (CertState, Coin) =
    if provider == null then return (CertState.empty, Coin.zero)

    val body = scalusTx.body.value

    // Collect unique credentials referenced by this transaction
    val creds = mutable.LinkedHashSet.empty[(Int, String)] // (credType, hash hex)

    // 1. Extract from withdrawals
    body.withdrawals match
      case Some(wdrl) =>
        wdrl.withdrawals.foreach { case (ra, _) =>
          extractCredFromRewardAccount(ra).foreach(creds.add)
        }
      case None => // no withdrawals

    // 2. Extract from certificates (TaggedOrderedStrictSet is opaque over IndexedSeq)
    val certs = body.certificates.asInstanceOf[IndexedSeq[Certificate]]
    certs.foreach { cert =>
      extractCredsFromCert(cert).foreach(creds.add)
    }

    // 3. Query provider for each credential and build maps
    var rewards = scala.collection.immutable.Map.empty[Credential, Coin]
    var deposits = scala.collection.immutable.Map.empty[Credential, Coin]
    var stakePools = scala.collection.immutable.Map.empty[Credential, PoolKeyHash]
    var dreps = scala.collection.immutable.Map.empty[Credential, DRep]

    for (credType, hash) <- creds do
      val scalusCred = toScalusCredential(credType, hash)

      provider.getRewardBalance(credType, hash).ifPresent { reward =>
        rewards = rewards.updated(scalusCred, Coin(reward.longValueExact()))
      }

      provider.getStakeDeposit(credType, hash).ifPresent { deposit =>
        deposits = deposits.updated(scalusCred, Coin(deposit.longValueExact()))
      }

      provider.getDelegatedPool(credType, hash).ifPresent { poolHash =>
        stakePools = stakePools.updated(scalusCred, PoolKeyHash.fromHex(poolHash))
      }

      provider.getDRepDelegation(credType, hash).ifPresent { drepDeleg =>
        dreps = dreps.updated(scalusCred, toScalusDRep(drepDeleg))
      }

    // 4. Build PoolsState from pools referenced in certificates
    val poolHashes = mutable.LinkedHashSet.empty[String]
    certs.foreach { cert =>
      extractPoolHashesFromCert(cert).foreach(poolHashes.add)
    }

    // Build PoolsState maps using JVM-level casts for opaque types
    // (PoolKeyHash, AddrKeyHash, VrfKeyHash, EpochNo are all opaque over ByteString/Long at JVM level)
    var poolStakePoolsRaw = scala.collection.immutable.Map.empty[ByteString, Certificate.PoolRegistration]
    var poolRetiringRaw = scala.collection.immutable.Map.empty[ByteString, Any]
    var poolDepositsRaw = scala.collection.immutable.Map.empty[ByteString, Coin]

    for poolHash <- poolHashes do
      val poolKeyBS = ByteString.fromHex(poolHash)
      if provider.isPoolRegistered(poolHash) then
        // Create a minimal PoolRegistration entry to mark the pool as registered.
        // Only existence in the map matters — field values are not inspected for
        // pool-exists checks (contains/get on the map key).
        val dummyReg = Certificate.PoolRegistration(
          operator = AddrKeyHash.fromHex(poolHash),
          vrfKeyHash = ByteString.empty.asInstanceOf[VrfKeyHash],
          pledge = Coin.zero,
          cost = Coin.zero,
          margin = UnitInterval(0, 1),
          rewardAccount = null.asInstanceOf[RewardAccount],
          poolOwners = scala.collection.immutable.Set.empty,
          relays = IndexedSeq.empty,
          poolMetadata = None
        )
        poolStakePoolsRaw = poolStakePoolsRaw.updated(poolKeyBS,
          dummyReg.asInstanceOf[Certificate.PoolRegistration])

        provider.getPoolDeposit(poolHash).ifPresent { dep =>
          poolDepositsRaw = poolDepositsRaw.updated(poolKeyBS, Coin(dep.longValueExact()))
        }

        provider.getPoolRetirementEpoch(poolHash).ifPresent { epoch =>
          poolRetiringRaw = poolRetiringRaw.updated(poolKeyBS, java.lang.Long.valueOf(epoch.longValue()))
        }

    // Cast the maps to the opaque-typed versions expected by PoolsState
    val poolsState = PoolsState(
      poolStakePoolsRaw.asInstanceOf[scala.collection.immutable.Map[PoolKeyHash, Certificate.PoolRegistration]],
      scala.collection.immutable.Map.empty, // futureStakePoolParams
      poolRetiringRaw.asInstanceOf[scala.collection.immutable.Map[PoolKeyHash, Long]],
      poolDepositsRaw.asInstanceOf[scala.collection.immutable.Map[PoolKeyHash, Coin]]
    )

    // 5. Build VotingState from DRep credentials referenced in certificates
    val drepCreds = mutable.LinkedHashSet.empty[(Int, String)]
    certs.foreach { cert =>
      extractDRepCredsFromCert(cert).foreach(drepCreds.add)
    }

    var drepStates = scala.collection.immutable.Map.empty[Credential, DRepState]
    for (credType, hash) <- drepCreds do
      if provider.isDRepRegistered(credType, hash) then
        val scalusCred = toScalusCredential(credType, hash)
        val deposit = provider.getDRepDeposit(credType, hash)
          .map(d => Coin(d.longValueExact()))
          .orElse(Coin.zero)
        drepStates = drepStates.updated(scalusCred,
          DRepState(0L, None, deposit, scala.collection.immutable.Set.empty))

    val votingState = VotingState(drepStates)

    val delegState = DelegationState(rewards, deposits, stakePools, dreps)
    val certState = CertState(votingState, poolsState, delegState)

    val totalDeposited = try
      val td = provider.getTotalDeposited()
      if td != null then Coin(td.longValueExact()) else Coin.zero
    catch
      case _: ArithmeticException => Coin.zero

    (certState, totalDeposited)

  private def extractCredFromRewardAccount(ra: RewardAccount): Option[(Int, String)] =
    val payload = ra.address.payload
    val hash = payload.asHash
    if hash == null then None
    else
      val credType = if payload.isScript then 1 else 0
      Some((credType, hash.toHex))

  /**
   * Extract DRep credentials from GOVCERT certificates.
   */
  private def extractDRepCredsFromCert(cert: Certificate): Seq[(Int, String)] =
    cert match
      case c: Certificate.RegDRepCert => credFromScalus(c.drepCredential)
      case c: Certificate.UnregDRepCert => credFromScalus(c.drepCredential)
      case c: Certificate.UpdateDRepCert => credFromScalus(c.drepCredential)
      case _ => Seq.empty

  /**
   * Extract pool key hashes from certificates that reference pools.
   */
  private def extractPoolHashesFromCert(cert: Certificate): Seq[String] =
    cert match
      case c: Certificate.PoolRegistration => Seq(c.operator.toHex)
      case c: Certificate.PoolRetirement => Seq(c.poolKeyHash.toHex)
      case c: Certificate.StakeDelegation => Seq(c.poolKeyHash.toHex)
      case c: Certificate.StakeRegDelegCert => Seq(c.poolKeyHash.toHex)
      case c: Certificate.StakeVoteDelegCert => Seq(c.poolKeyHash.toHex)
      case c: Certificate.StakeVoteRegDelegCert => Seq(c.poolKeyHash.toHex)
      case _ => Seq.empty

  private def extractCredsFromCert(cert: Certificate): Seq[(Int, String)] =
    cert match
      case c: Certificate.RegCert => credFromScalus(c.credential)
      case c: Certificate.UnregCert => credFromScalus(c.credential)
      case c: Certificate.StakeDelegation => credFromScalus(c.credential)
      case c: Certificate.StakeRegDelegCert => credFromScalus(c.credential)
      case c: Certificate.StakeVoteDelegCert => credFromScalus(c.credential)
      case c: Certificate.StakeVoteRegDelegCert => credFromScalus(c.credential)
      case c: Certificate.VoteDelegCert => credFromScalus(c.credential)
      case c: Certificate.VoteRegDelegCert => credFromScalus(c.credential)
      case _ => Seq.empty

  private def credFromScalus(cred: Credential): Seq[(Int, String)] =
    cred match
      case Credential.KeyHash(h) => Seq((0, h.toHex))
      case Credential.ScriptHash(h) => Seq((1, h.toHex))

  private def toScalusCredential(credType: Int, hash: String): Credential =
    if credType == 1 then Credential.ScriptHash(ScriptHash.fromHex(hash))
    else Credential.KeyHash(AddrKeyHash.fromHex(hash))

  private def toScalusDRep(d: DRepDelegation): DRep = d.drepType() match
    case 0 => DRep.KeyHash(AddrKeyHash.fromHex(d.hash()))
    case 1 => DRep.ScriptHash(ScriptHash.fromHex(d.hash()))
    case 2 => DRep.AlwaysAbstain
    case 3 => DRep.AlwaysNoConfidence
    case _ => DRep.AlwaysAbstain // fallback
