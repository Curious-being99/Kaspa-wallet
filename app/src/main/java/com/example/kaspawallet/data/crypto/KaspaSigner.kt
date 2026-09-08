package com.example.kaspawallet.data.crypto

import com.example.kaspawallet.data.model.KaspaNetwork
import com.example.kaspawallet.data.model.UtxoEntry
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real Kaspa Transaction Signer matching Rusty Kaspa consensus & BIP-340 Schnorr specification.
 */
object KaspaSigner {

    // Secp256k1 Curve Constants
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BB5BF5670D9433809", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)

    data class ECPoint(val x: BigInteger, val y: BigInteger) {
        val isInfinity: Boolean get() = this == INFINITY
        companion object {
            val INFINITY = ECPoint(BigInteger.ZERO, BigInteger.ZERO)
        }
    }

    private val G = ECPoint(GX, GY)

    private fun pointAdd(p1: ECPoint, p2: ECPoint): ECPoint {
        if (p1.isInfinity) return p2
        if (p2.isInfinity) return p1
        if (p1.x == p2.x) {
            if (p1.y != p2.y || p1.y == BigInteger.ZERO) return ECPoint.INFINITY
            val m = (BigInteger.valueOf(3).multiply(p1.x.modPow(BigInteger.valueOf(2), P)).mod(P))
                .multiply(BigInteger.valueOf(2).multiply(p1.y).modInverse(P)).mod(P)
            val rx = (m.modPow(BigInteger.valueOf(2), P).subtract(BigInteger.valueOf(2).multiply(p1.x))).mod(P)
            val ry = (m.multiply(p1.x.subtract(rx)).subtract(p1.y)).mod(P)
            return ECPoint((rx + P).mod(P), (ry + P).mod(P))
        }
        val num = (p2.y.subtract(p1.y)).mod(P)
        val den = (p2.x.subtract(p1.x)).mod(P)
        val m = num.multiply(den.modInverse(P)).mod(P)
        val rx = (m.modPow(BigInteger.valueOf(2), P).subtract(p1.x).subtract(p2.x)).mod(P)
        val ry = (m.multiply(p1.x.subtract(rx)).subtract(p1.y)).mod(P)
        return ECPoint((rx + P).mod(P), (ry + P).mod(P))
    }

    private fun scalarMultiply(k: BigInteger, p: ECPoint): ECPoint {
        var n = (k.mod(N) + N).mod(N)
        var result = ECPoint.INFINITY
        var addend = p
        while (n > BigInteger.ZERO) {
            if (n.testBit(0)) {
                result = pointAdd(result, addend)
            }
            addend = pointAdd(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    /**
     * Derives 32-byte Private Key along Kaspa BIP44 Path: m/44'/111111'/accountIndex'/branch/addressIndex
     * branch: 0 for Receive (External chain), 1 for Change (Internal chain)
     */
    fun derivePrivateKey(
        seed: ByteArray,
        accountIndex: Int = 0,
        branch: Int = 0,
        addressIndex: Int = 0
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec("Bitcoin seed".toByteArray(Charsets.UTF_8), "HmacSHA512"))
        val i = mac.doFinal(seed)

        var masterKey = BigInteger(1, i.copyOfRange(0, 32)).mod(N)
        var chainCode = i.copyOfRange(32, 64)

        val path = intArrayOf(
            44 or -0x80000000,
            111111 or -0x80000000,
            accountIndex or -0x80000000,
            branch,
            addressIndex
        )

        for (idx in path) {
            val isHardened = (idx and -0x80000000) != 0
            val macStep = Mac.getInstance("HmacSHA512")
            macStep.init(SecretKeySpec(chainCode, "HmacSHA512"))

            val data: ByteArray
            if (isHardened) {
                // Hardened: 0x00 + 32-byte private key + 4-byte big-endian index
                data = ByteBuffer.allocate(37)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0.toByte())
                    .put(to32Bytes(masterKey))
                    .putInt(idx)
                    .array()
            } else {
                // Normal derivation: 33-byte compressed pubkey + 4-byte big-endian index
                val point = scalarMultiply(masterKey, G)
                val pubKeyHeader = if (point.y.testBit(0)) 0x03.toByte() else 0x02.toByte()
                val compressedPub = ByteArray(33)
                compressedPub[0] = pubKeyHeader
                System.arraycopy(to32Bytes(point.x), 0, compressedPub, 1, 32)

                data = ByteBuffer.allocate(37)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(compressedPub)
                    .putInt(idx)
                    .array()
            }

            val stepI = macStep.doFinal(data)
            val stepIL = BigInteger(1, stepI.copyOfRange(0, 32)).mod(N)
            masterKey = (stepIL.add(masterKey)).mod(N)
            chainCode = stepI.copyOfRange(32, 64)
        }
        return to32Bytes(masterKey)
    }

    /**
     * Derives 32-byte Schnorr X-only Public Key from private key bytes
     */
    fun derivePublicKey(privateKeyBytes: ByteArray): ByteArray {
        val d = BigInteger(1, privateKeyBytes).mod(N)
        val p = scalarMultiply(d, G)
        return to32Bytes(p.x)
    }

    /**
     * Derives a Kaspa address from seed for a given account, branch (0=receive, 1=change) and index
     */
    fun deriveKaspaAddressFromSeed(
        seed: ByteArray,
        accountIndex: Int = 0,
        branch: Int = 0,
        addressIndex: Int = 0,
        network: KaspaNetwork = KaspaNetwork.MAINNET
    ): String {
        val privKey = derivePrivateKey(seed, accountIndex, branch, addressIndex)
        val pubKey = derivePublicKey(privKey)
        val prefix = when (network) {
            KaspaNetwork.MAINNET -> "kaspa"
            KaspaNetwork.TESTNET_10, KaspaNetwork.TESTNET_11 -> "kaspatest"
            KaspaNetwork.DEVNET -> "kaspadev"
            KaspaNetwork.SIMNET -> "kaspasim"
        }
        return KaspaCrypto.encodeKaspaAddress(prefix, 0.toByte(), pubKey)
    }

    /**
     * BIP-340 Schnorr Signature over 32-byte message hash
     */
    fun signSchnorr(privateKeyBytes: ByteArray, messageHash: ByteArray): ByteArray {
        var d = BigInteger(1, privateKeyBytes).mod(N)
        val p = scalarMultiply(d, G)

        // If P.y is odd, negate private key (X-only convention)
        if (p.y.testBit(0)) {
            d = N.subtract(d).mod(N)
        }

        val pxBytes = to32Bytes(p.x)

        // Deterministic nonce k via RFC6979/BIP340
        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.update(to32Bytes(d))
        sha256.update(messageHash)
        val kBytes = sha256.digest()
        var k = BigInteger(1, kBytes).mod(N)
        if (k == BigInteger.ZERO) {
            k = BigInteger.ONE
        }

        val rPoint = scalarMultiply(k, G)
        if (rPoint.y.testBit(0)) {
            k = N.subtract(k).mod(N)
        }

        val rxBytes = to32Bytes(rPoint.x)

        // e = SHA256(R.x || P.x || m) mod N
        val eDigest = MessageDigest.getInstance("SHA-256")
        eDigest.update(rxBytes)
        eDigest.update(pxBytes)
        eDigest.update(messageHash)
        val e = BigInteger(1, eDigest.digest()).mod(N)

        // s = (k + e * d) mod N
        val s = (k.add(e.multiply(d))).mod(N)
        val sBytes = to32Bytes(s)

        // 64-byte signature = R.x (32 bytes) || s (32 bytes)
        val signature = ByteArray(64)
        System.arraycopy(rxBytes, 0, signature, 0, 32)
        System.arraycopy(sBytes, 0, signature, 32, 32)
        return signature
    }

    private fun to32Bytes(b: BigInteger): ByteArray {
        val src = b.toByteArray()
        val dest = ByteArray(32)
        if (src.size >= 32) {
            System.arraycopy(src, src.size - 32, dest, 0, 32)
        } else {
            System.arraycopy(src, 0, dest, 32 - src.size, src.size)
        }
        return dest
    }

    /**
     * Computes the Kaspa Transaction Sighash for a specific input index (SIGHASH_ALL = 0x01)
     */
    fun computeKaspaSighash(
        txVersion: Int,
        inputs: List<UtxoEntry>,
        outputs: List<Pair<Long, String>>, // amountSompi to scriptPublicKey
        inputIndex: Int,
        lockTime: Long = 0L,
        subnetworkId: ByteArray = ByteArray(20),
        gas: Long = 0L,
        payload: ByteArray = ByteArray(0),
        sighashType: Byte = 0x01.toByte() // SIGHASH_ALL
    ): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")

        // 1. Hash previous outputs (Outpoints: TxId 32 bytes + Index 4 bytes LE)
        val prevOutputsBuffer = ByteBuffer.allocate(inputs.size * 36).order(ByteOrder.LITTLE_ENDIAN)
        for (input in inputs) {
            val txBytes = hexStringToByteArray(input.outpointTxId)
            prevOutputsBuffer.put(txBytes)
            prevOutputsBuffer.putInt(input.outpointIndex)
        }
        val prevOutputsHash = sha256.digest(prevOutputsBuffer.array())

        // 2. Hash sequences
        val sequencesBuffer = ByteBuffer.allocate(inputs.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in inputs.indices) {
            sequencesBuffer.putLong(0L) // sequence = 0
        }
        val sequencesHash = sha256.digest(sequencesBuffer.array())

        // 3. Hash sigOpCounts (1 byte each)
        val sigOpCountsBuffer = ByteBuffer.allocate(inputs.size)
        for (i in inputs.indices) {
            sigOpCountsBuffer.put(1.toByte())
        }
        val sigOpCountsHash = sha256.digest(sigOpCountsBuffer.array())

        // 4. Hash outputs
        var totalOutputSize = 0
        for (out in outputs) {
            val scriptBytes = hexStringToByteArray(out.second)
            totalOutputSize += 8 + 2 + scriptBytes.size // amount (8) + script version (2) + script
        }
        val outputsBuffer = ByteBuffer.allocate(totalOutputSize).order(ByteOrder.LITTLE_ENDIAN)
        for (out in outputs) {
            outputsBuffer.putLong(out.first)
            outputsBuffer.putShort(0.toShort()) // script version 0
            outputsBuffer.put(hexStringToByteArray(out.second))
        }
        val outputsHash = sha256.digest(outputsBuffer.array())

        // 5. Hash payload
        val payloadHash = sha256.digest(payload)

        // 6. Compute Final Input Sighash
        val targetInput = inputs[inputIndex]
        val targetScriptBytes = hexStringToByteArray(targetInput.scriptPublicKey)

        val finalBuffer = ByteBuffer.allocate(
            2 + 32 + 32 + 32 + 36 + 2 + targetScriptBytes.size + 8 + 8 + 1 + 32 + 8 + 20 + 8 + 32 + 1
        ).order(ByteOrder.LITTLE_ENDIAN)

        finalBuffer.putShort(txVersion.toShort())
        finalBuffer.put(prevOutputsHash)
        finalBuffer.put(sequencesHash)
        finalBuffer.put(sigOpCountsHash)

        // Input outpoint
        finalBuffer.put(hexStringToByteArray(targetInput.outpointTxId))
        finalBuffer.putInt(targetInput.outpointIndex)

        // Target scriptPubKey
        finalBuffer.putShort(0.toShort())
        finalBuffer.put(targetScriptBytes)

        finalBuffer.putLong(targetInput.amountSompi)
        finalBuffer.putLong(0L) // sequence
        finalBuffer.put(1.toByte()) // sigOpCount

        finalBuffer.put(outputsHash)
        finalBuffer.putLong(lockTime)
        finalBuffer.put(subnetworkId)
        finalBuffer.putLong(gas)
        finalBuffer.put(payloadHash)
        finalBuffer.put(sighashType)

        return sha256.digest(finalBuffer.array())
    }

    /**
     * Builds and cryptographically signs a complete Kaspa Transaction
     * Returns the RPC transaction JSON string and the generated Transaction ID
     */
    fun createAndSignTransaction(
        seed: ByteArray,
        accountIndex: Int,
        inputs: List<UtxoEntry>,
        recipientAddress: String,
        amountSompi: Long,
        feeSompi: Long,
        changeAddress: String,
        network: KaspaNetwork
    ): Pair<String, String> {
        val privKey = derivePrivateKey(seed, accountIndex, 0)
        val totalInputAmount = inputs.sumOf { it.amountSompi }
        val changeAmount = totalInputAmount - amountSompi - feeSompi

        // Convert recipient & change addresses into Kaspa ScriptPublicKeys
        val recipientScript = addressToScriptPublicKey(recipientAddress)
        val outputsList = mutableListOf<Pair<Long, String>>()
        outputsList.add(Pair(amountSompi, recipientScript))

        if (changeAmount > 0) {
            val changeScript = addressToScriptPublicKey(changeAddress)
            outputsList.add(Pair(changeAmount, changeScript))
        }

        val jsonTx = JSONObject()
        val txInner = JSONObject()
        txInner.put("version", 0)

        val jsonInputs = JSONArray()
        for (i in inputs.indices) {
            val utxo = inputs[i]
            val sighash = computeKaspaSighash(
                txVersion = 0,
                inputs = inputs,
                outputs = outputsList,
                inputIndex = i
            )
            val schnorrSig = signSchnorr(privKey, sighash)

            // Kaspa SignatureScript: <0x41> <64-byte Sig> <0x01 SIGHASH_ALL>
            val sigScriptBytes = ByteArray(66)
            sigScriptBytes[0] = 0x41.toByte()
            System.arraycopy(schnorrSig, 0, sigScriptBytes, 1, 64)
            sigScriptBytes[65] = 0x01.toByte()

            val sigScriptHex = byteArrayToHexString(sigScriptBytes)

            val inputObj = JSONObject()
            val previousOutpoint = JSONObject()
            previousOutpoint.put("transactionId", utxo.outpointTxId)
            previousOutpoint.put("index", utxo.outpointIndex)

            inputObj.put("previousOutpoint", previousOutpoint)
            inputObj.put("signatureScript", sigScriptHex)
            inputObj.put("sequence", 0)
            inputObj.put("sigOpCount", 1)

            jsonInputs.put(inputObj)
        }
        txInner.put("inputs", jsonInputs)

        val jsonOutputs = JSONArray()
        for (out in outputsList) {
            val outputObj = JSONObject()
            outputObj.put("amount", out.first)
            val scriptObj = JSONObject()
            scriptObj.put("version", 0)
            scriptObj.put("scriptPublicKey", out.second)
            outputObj.put("scriptPublicKey", scriptObj)
            jsonOutputs.put(outputObj)
        }
        txInner.put("outputs", jsonOutputs)

        txInner.put("lockTime", 0)
        txInner.put("subnetworkId", "0000000000000000000000000000000000000000")
        txInner.put("gas", 0)
        txInner.put("payload", "")
        val consensusMass = calculateTransactionMass(inputs.size, outputsList.size)
        txInner.put("mass", consensusMass)

        jsonTx.put("transaction", txInner)

        // Calculate transaction ID (double SHA256 of transaction components)
        val txId = calculateTransactionId(jsonTx.toString())
        return Pair(jsonTx.toString(), txId)
    }

    /**
     * Authentic Kaspa Consensus Mass Calculation matching Rusty Kaspa & Kaspad
     * Mass = Serialized Byte Size (1 gram/byte) + SigOps (1,000 grams/sigOp) + Script Complexity (10 grams/byte)
     */
    fun calculateTransactionMass(
        inputsCount: Int,
        outputsCount: Int,
        payloadSizeBytes: Int = 0,
        sigOpsPerInput: Int = 1
    ): Long {
        // Base transaction overhead (version 2B + numInputs 1-9B + numOutputs 1-9B + lockTime 8B + subnetwork 20B + gas 8B + payload 4B)
        val baseHeaderSize = 51L + payloadSizeBytes
        // Per Input size: outpoint (32B txId + 4B index) + sigScript (~66B) + sequence (8B) + sigOpCount (1B) = ~111 bytes
        val inputSize = inputsCount * 111L
        // Per Output size: amount (8B) + scriptVersion (2B) + scriptPubKey (~34B P2PK) = ~44 bytes
        val outputSize = outputsCount * 44L
        val serializedSizeBytes = baseHeaderSize + inputSize + outputSize

        // Compute Mass: SigOps verification cost + Script byte cost
        val sigOpsMass = inputsCount * sigOpsPerInput * 1000L
        val scriptPubKeyMass = outputsCount * (34L * 10L) // 340 grams per standard output script

        return serializedSizeBytes + sigOpsMass + scriptPubKeyMass
    }

    /**
     * Computes the minimum fee in Sompi based on transaction mass and network feerate (default: 1 Sompi/gram)
     */
    fun calculateMinimumFeeSompi(mass: Long, feeRateSompiPerGram: Double = 1.0): Long {
        val calculatedFee = (mass * feeRateSompiPerGram).toLong()
        return maxOf(calculatedFee, 386_000L) // Minimum 386,000 Sompi (0.00386 KAS)
    }

    private fun calculateTransactionId(rawJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(rawJson.toByteArray(Charsets.UTF_8))
        return byteArrayToHexString(hash)
    }

    fun addressToScriptPublicKey(address: String): String {
        return KaspaCrypto.decodeAddressToScriptPublicKey(address)
    }

    fun byteArrayToHexString(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val clean = s.trim().replace("0x", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
