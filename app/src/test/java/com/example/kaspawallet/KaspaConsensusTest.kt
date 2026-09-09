package com.example.kaspawallet

import com.example.kaspawallet.data.crypto.Blake2b
import com.example.kaspawallet.data.crypto.KaspaSigner
import com.example.kaspawallet.data.model.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class KaspaConsensusTest {

    @Test
    fun testBlake2bKeyed() {
        val b = Blake2b("TransactionSigningHash".toByteArray(Charsets.UTF_8), 32)
        b.update("abc".toByteArray(Charsets.UTF_8))
        val hex = KaspaSigner.byteArrayToHexString(b.finalize())
        assertEquals("1d25f7b19571ef69f5a7fac4494f74bb9c410ec485570beefe5a7e8d11fcc465", hex)
    }

    @Test
    fun testRustyKaspaSighashVector() {
        // Exact test vector from rusty-kaspa consensus/core/src/hashing/sighash.rs ("native-all-0")
        val prevTxId = "880eb9819a31821d9d2399e2f35e2433b72637e393d71ecc9b8d0250f49153c3"
        val scriptPubKey1 = "208325613d2eeaf7176ac6c670b13c0043156c427438ed72d74b7800862ad884e8ac"
        val scriptPubKey2 = "20fcef4c106cf11135bbd70f02a726a92162d2fb8b22f0469126f800862ad884e8ac"

        val inputs = listOf(
            UtxoEntry(outpointTxId = prevTxId, outpointIndex = 0, amountSompi = 100, scriptPublicKey = scriptPubKey1, blockDaaScore = 0, isCoinbase = false),
            UtxoEntry(outpointTxId = prevTxId, outpointIndex = 1, amountSompi = 200, scriptPublicKey = scriptPubKey1, blockDaaScore = 0, isCoinbase = false),
            UtxoEntry(outpointTxId = prevTxId, outpointIndex = 2, amountSompi = 300, scriptPublicKey = scriptPubKey1, blockDaaScore = 0, isCoinbase = false)
        )
        val outputs = listOf(
            Pair(300L, scriptPubKey2),
            Pair(300L, scriptPubKey1)
        )

        val sighash = KaspaSigner.computeKaspaSighash(
            txVersion = 0,
            inputs = inputs,
            outputs = outputs,
            inputIndex = 0,
            sequences = listOf(0L, 1L, 2L),
            sigOpCounts = listOf(0, 0, 0),
            lockTime = 1615462089000L,
            subnetworkId = ByteArray(20),
            gas = 0L,
            payload = ByteArray(0),
            sighashType = 0x01.toByte()
        )

        val sighashHex = KaspaSigner.byteArrayToHexString(sighash)
        assertEquals("03b7ac6927b2b67100734c3cc313ff8c2e8b3ce3e746d46dd660b706a916b1f5", sighashHex)
    }

    @Test
    fun testKaspaTxIdVector() {
        // Exact test vector #1 from rusty-kaspa consensus/core/src/hashing/tx.rs:
        // tx: version 0, inputs: empty, outputs: empty, lock_time: 0, subnetwork_id: 20 zeros, gas: 0, payload: empty
        // expected_id: "2c18d5e59ca8fc4c23d9560da3bf738a8f40935c11c162017fbf2c907b7e665c"
        val txId = KaspaSigner.computeTransactionId(
            txVersion = 0,
            inputs = emptyList(),
            outputs = emptyList(),
            lockTime = 0L,
            subnetworkId = ByteArray(20),
            gas = 0L,
            payload = ByteArray(0)
        )
        assertEquals("2c18d5e59ca8fc4c23d9560da3bf738a8f40935c11c162017fbf2c907b7e665c", txId)
    }

    @Test
    fun testBip340SchnorrSigningAndVerification() {
        val privKey = KaspaSigner.to32Bytes(BigInteger("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF", 16))
        val pubKey = KaspaSigner.derivePublicKey(privKey)
        val msgHash = KaspaSigner.to32Bytes(BigInteger("243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89", 16))

        val sig = KaspaSigner.signSchnorr(privKey, msgHash)
        assertEquals(64, sig.size)

        val verified = KaspaSigner.verifySchnorr(pubKey, msgHash, sig)
        assertTrue("Schnorr signature must be cryptographically valid", verified)
    }

    @Test
    fun testKaspaQrPayloadParsing() {
        val raw1 = "kaspa:qq8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7q"
        val (addr1, amt1) = com.example.kaspawallet.ui.components.parseKaspaQrPayload(raw1)
        assertEquals("kaspa:qq8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7q", addr1)
        assertEquals(null, amt1)

        val raw2 = "kaspa:qq8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7q?amount=123.45"
        val (addr2, amt2) = com.example.kaspawallet.ui.components.parseKaspaQrPayload(raw2)
        assertEquals("kaspa:qq8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7q", addr2)
        assertEquals(123.45, amt2!!, 0.00001)
    }

    @Test
    fun testKaspaAddressOrUriValidation() {
        assertTrue(com.example.kaspawallet.ui.components.isKaspaAddressOrUri("kaspa:qq8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7q"))
        assertTrue(com.example.kaspawallet.ui.components.isKaspaAddressOrUri("kaspa:qq8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7q?amount=10"))
        assertTrue(com.example.kaspawallet.ui.components.isKaspaAddressOrUri("kaspatest:qp8l8xy3h967q94z48yv6z2g48q32z7y72g48q32z7"))
        
        // Ensure arbitrary non-wallet strings are rejected by the camera scanner
        org.junit.Assert.assertFalse(com.example.kaspawallet.ui.components.isKaspaAddressOrUri("https://google.com"))
        org.junit.Assert.assertFalse(com.example.kaspawallet.ui.components.isKaspaAddressOrUri("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        org.junit.Assert.assertFalse(com.example.kaspawallet.ui.components.isKaspaAddressOrUri("WIFI:S:MyNetwork;T:WPA;P:secret;;"))
    }
}
