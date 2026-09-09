package com.example.kaspawallet.data.repository

import android.util.Log
import com.example.kaspawallet.data.api.KaspaApiClient
import com.example.kaspawallet.data.crypto.KaspaCrypto
import com.example.kaspawallet.data.crypto.KaspaSigner
import com.example.kaspawallet.data.crypto.KaspaUtils
import com.example.kaspawallet.data.local.KaspaDatabase
import com.example.kaspawallet.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class KaspaWalletRepository(
    val database: KaspaDatabase,
    val apiClient: KaspaApiClient = KaspaApiClient()
) {
    val allWallets: Flow<List<WalletEntity>> = database.walletDao().getAllWallets()
    val allContacts: Flow<List<ContactEntity>> = database.contactDao().getAllContacts()

    private val _currentNetwork = MutableStateFlow(KaspaNetwork.MAINNET)
    val currentNetwork: StateFlow<KaspaNetwork> = _currentNetwork.asStateFlow()

    private val _blockDagInfo = MutableStateFlow(BlockDagInfo())
    val blockDagInfo: StateFlow<BlockDagInfo> = _blockDagInfo.asStateFlow()

    private val _marketInfo = MutableStateFlow(KaspaMarketInfo())
    val marketInfo: StateFlow<KaspaMarketInfo> = _marketInfo.asStateFlow()

    private val _activeWalletId = MutableStateFlow<String?>(null)
    val activeWalletId: StateFlow<String?> = _activeWalletId.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    // UTXOs state mapped by accountId
    private val _accountUtxos = MutableStateFlow<Map<String, List<UtxoEntry>>>(emptyMap())
    val accountUtxos: StateFlow<Map<String, List<UtxoEntry>>> = _accountUtxos.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    init {
        // Start background live sync for BlockDAG metrics and Market Price
        startPeriodicSync()
    }

    private fun startPeriodicSync() {
        repositoryScope.launch {
            while (isActive) {
                try {
                    syncNetworkMetrics()
                    syncMarketPrice()

                    // If active account exists, sync its live on-chain balance & UTXOs
                    val currentAccId = _activeAccountId.value
                    if (currentAccId != null) {
                        syncAccountOnChain(currentAccId)
                    }
                } catch (e: Exception) {
                    Log.w("KaspaWalletRepository", "Periodic sync warning: ${e.message}")
                }
                delay(12000) // Poll real network every 12 seconds
            }
        }
    }

    suspend fun syncNetworkMetrics() {
        try {
            val liveDag = apiClient.fetchBlockDagInfo(_currentNetwork.value)
            if (liveDag.blockCount > 0) {
                _blockDagInfo.value = liveDag
            }
        } catch (e: Exception) {
            Log.w("KaspaWalletRepository", "Network metrics update skipped: ${e.message}")
        }
    }

    suspend fun syncMarketPrice() {
        val liveMarket = apiClient.fetchMarketPrice()
        if (liveMarket.priceUsd > 0) {
            _marketInfo.value = liveMarket
        }
    }

    suspend fun syncAccountOnChain(accountId: String) = withContext(Dispatchers.IO) {
        val account = database.accountDao().getAccountById(accountId) ?: return@withContext
        val network = _currentNetwork.value
        
        // 1. Fetch real balance from Kaspa node
        val realBalanceSompi = apiClient.fetchAddressBalance(account.address, network)
        if (realBalanceSompi != account.balanceSompi) {
            database.accountDao().updateBalance(accountId, realBalanceSompi)
        }

        // 2. Fetch real UTXOs
        val liveUtxos = apiClient.fetchAddressUtxos(account.address, network)
        _accountUtxos.update { current ->
            current + (accountId to liveUtxos)
        }

        // 3. Fetch real Transactions
        val liveTxs = apiClient.fetchAddressTransactions(account.address, account.walletId, accountId, network)
        if (liveTxs.isNotEmpty()) {
            for (tx in liveTxs) {
                database.transactionDao().insertTransaction(tx)
            }
        }

        // 4. Auto-recover / sweep any funds sitting on secondary address indices (e.g. branch 0 index 1..4 or branch 1 index 0..4)
        try {
            val wallet = database.walletDao().getWalletById(account.walletId)
            val words = wallet?.encryptedMnemonic?.split(" ") ?: emptyList()
            if (words.size in listOf(12, 24)) {
                val seed = KaspaCrypto.mnemonicToSeed(words)
                
                // Scan up to 30 address gap limit across receive (branch 0) and change (branch 1) chains
                val gapLimit = 30
                val branchesToScan = listOf(
                    0 to (1 until gapLimit).toList(), // m/44'/111111'/0'/0/1..29
                    1 to (0 until gapLimit).toList()  // m/44'/111111'/0'/1/0..29
                )

                for ((branch, indices) in branchesToScan) {
                    for (addrIdx in indices) {
                        val derivedAddr = if (branch == 0) {
                            KaspaCrypto.deriveKaspaAddress(words, account.accountIndex, addrIdx, network)
                        } else {
                            KaspaCrypto.deriveKaspaChangeAddress(words, account.accountIndex, addrIdx, network)
                        }

                        if (derivedAddr.isNotBlank() && derivedAddr != account.address) {
                            val utxos = apiClient.fetchAddressUtxos(derivedAddr, network)
                            val totalSompi = utxos.sumOf { it.amountSompi }
                            val mass = KaspaSigner.calculateTransactionMass(utxos.size, 1)
                            val feeSompi = KaspaSigner.calculateMinimumFeeSompi(mass)
                            if (totalSompi > feeSompi) {
                                val sweepAmount = totalSompi - feeSompi
                                val (signedSweepTx, sweepTxId) = KaspaSigner.createAndSignTransaction(
                                    seed = seed,
                                    accountIndex = account.accountIndex,
                                    inputs = utxos,
                                    recipientAddress = account.address,
                                    amountSompi = sweepAmount,
                                    feeSompi = feeSompi,
                                    changeAddress = account.address,
                                    network = network,
                                    inputBranch = branch,
                                    inputAddressIndex = addrIdx
                                )
                                val (sweepOk, _) = apiClient.broadcastTransaction(signedSweepTx, network)
                                if (sweepOk) {
                                    Log.i("KaspaWalletRepository", "Swept funds from $derivedAddr (branch $branch idx $addrIdx) to primary: $sweepTxId")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("KaspaWalletRepository", "Multi-index auto-recovery check: ${e.message}")
        }
    }

    fun getAccountsForWallet(walletId: String): Flow<List<AccountEntity>> {
        return database.accountDao().getAccountsForWallet(walletId)
    }

    fun getTransactionsForWallet(walletId: String): Flow<List<TransactionEntity>> {
        return database.transactionDao().getTransactionsForWallet(walletId)
    }

    fun getTransactionsForAccount(accountId: String): Flow<List<TransactionEntity>> {
        return database.transactionDao().getTransactionsForAccount(accountId)
    }

    fun setNetwork(network: KaspaNetwork) {
        _currentNetwork.value = network
        repositoryScope.launch {
            syncNetworkMetrics()
            val currentAcc = _activeAccountId.value
            if (currentAcc != null) {
                syncAccountOnChain(currentAcc)
            }
        }
    }

    fun setCustomRpcEndpoint(url: String?) {
        apiClient.customEndpoint = url
        repositoryScope.launch {
            syncNetworkMetrics()
        }
    }

    fun setActiveWallet(walletId: String?) {
        _activeWalletId.value = walletId
    }

    fun setActiveAccount(accountId: String?) {
        _activeAccountId.value = accountId
        if (accountId != null) {
            repositoryScope.launch {
                syncAccountOnChain(accountId)
            }
        }
    }

    suspend fun createWallet(
        name: String,
        mnemonicWords: List<String>,
        hasPassphrase: Boolean = false,
        passphrase: String = ""
    ): Pair<WalletEntity, AccountEntity> = withContext(Dispatchers.IO) {
        val walletId = UUID.randomUUID().toString()
        val wallet = WalletEntity(
            id = walletId,
            name = name,
            encryptedMnemonic = mnemonicWords.joinToString(" "),
            wordCount = mnemonicWords.size,
            hasPassphrase = hasPassphrase,
            isLocked = false
        )
        database.walletDao().insertWallet(wallet)

        // Create default Primary account (#0)
        val accountId = UUID.randomUUID().toString()
        val address = KaspaUtils.generateDeterministicAddress(
            mnemonicWords = mnemonicWords,
            accountIndex = 0,
            network = _currentNetwork.value,
            passphrase = passphrase
        )
        
        // Fetch real on-chain balance (0 Sompi for fresh or real balance if imported)
        val realOnChainBalance = apiClient.fetchAddressBalance(address, _currentNetwork.value)

        val primaryAccount = AccountEntity(
            id = accountId,
            walletId = walletId,
            accountIndex = 0,
            name = "Primary Account (#0)",
            address = address,
            balanceSompi = realOnChainBalance,
            accountType = "BIP44 Standard",
            derivationPath = "m/44'/111111'/0'/0/0",
            colorIndex = 0
        )
        database.accountDao().insertAccount(primaryAccount)

        // Fetch UTXOs and txs from live network
        val liveUtxos = apiClient.fetchAddressUtxos(address, _currentNetwork.value)
        _accountUtxos.update { current ->
            current + (accountId to liveUtxos)
        }

        val liveTxs = apiClient.fetchAddressTransactions(address, walletId, accountId, _currentNetwork.value)
        for (tx in liveTxs) {
            database.transactionDao().insertTransaction(tx)
        }

        _activeWalletId.value = walletId
        _activeAccountId.value = accountId

        Pair(wallet, primaryAccount)
    }

    suspend fun createAccount(
        walletId: String,
        name: String,
        accountIndex: Int
    ): AccountEntity = withContext(Dispatchers.IO) {
        val wallet = database.walletDao().getWalletById(walletId)
            ?: throw IllegalStateException("Wallet not found")

        val words = wallet.encryptedMnemonic.split(" ")
        val address = KaspaUtils.generateDeterministicAddress(words, accountIndex, _currentNetwork.value)
        val accountId = UUID.randomUUID().toString()

        val realOnChainBalance = apiClient.fetchAddressBalance(address, _currentNetwork.value)

        val newAccount = AccountEntity(
            id = accountId,
            walletId = walletId,
            accountIndex = accountIndex,
            name = name,
            address = address,
            balanceSompi = realOnChainBalance,
            accountType = "BIP44 Sub-Account",
            derivationPath = "m/44'/111111'/$accountIndex'/0/0",
            colorIndex = accountIndex % 5
        )
        database.accountDao().insertAccount(newAccount)

        val liveUtxos = apiClient.fetchAddressUtxos(address, _currentNetwork.value)
        _accountUtxos.update { current ->
            current + (accountId to liveUtxos)
        }

        _activeAccountId.value = accountId
        newAccount
    }

    suspend fun getAccountChangeAddress(accountId: String, index: Int = 0): String = withContext(Dispatchers.IO) {
        val account = database.accountDao().getAccountById(accountId) ?: return@withContext ""
        val wallet = database.walletDao().getWalletById(account.walletId) ?: return@withContext account.address
        val words = wallet.encryptedMnemonic.split(" ")
        if (words.isEmpty()) return@withContext account.address
        KaspaCrypto.deriveKaspaChangeAddress(
            mnemonic = words,
            accountIndex = account.accountIndex,
            addressIndex = index,
            network = _currentNetwork.value
        )
    }

    suspend fun deriveAddressForAccount(accountId: String, branch: Int, index: Int): String = withContext(Dispatchers.IO) {
        val account = database.accountDao().getAccountById(accountId) ?: return@withContext ""
        val wallet = database.walletDao().getWalletById(account.walletId) ?: return@withContext account.address
        val words = wallet.encryptedMnemonic.split(" ")
        if (words.isEmpty()) return@withContext account.address
        val seed = KaspaCrypto.mnemonicToSeed(words)
        KaspaSigner.deriveKaspaAddressFromSeed(
            seed = seed,
            accountIndex = account.accountIndex,
            branch = branch,
            addressIndex = index,
            network = _currentNetwork.value
        )
    }

    suspend fun sendKas(
        senderAccount: AccountEntity,
        recipientAddress: String,
        amountSompi: Long,
        feeSompi: Long,
        note: String,
        manualUtxos: List<UtxoEntry>? = null
    ): TransactionEntity = withContext(Dispatchers.IO) {
        val totalDebit = amountSompi + feeSompi
        if (senderAccount.balanceSompi < totalDebit) {
            throw IllegalArgumentException("Insufficient funds. Available: ${senderAccount.balanceSompi} Sompi, Required: $totalDebit Sompi")
        }

        val wallet = database.walletDao().getWalletById(senderAccount.walletId)
        val words = wallet?.encryptedMnemonic?.split(" ") ?: emptyList()
        val seed = if (words.isNotEmpty()) KaspaCrypto.mnemonicToSeed(words) else ByteArray(64)

        // Change output returns directly to the sender's account address
        // This ensures the sender's account only has (amountSompi + feeSompi) deducted, and the remaining change stays in the account balance
        val changeAddress = senderAccount.address

        val accountUtxosList = _accountUtxos.value[senderAccount.id] ?: emptyList()
        // If cached UTXOs are empty or sum is insufficient, fetch live from Kaspa network
        val availableUtxos = if (accountUtxosList.isEmpty() || accountUtxosList.sumOf { it.amountSompi } < totalDebit) {
            val live = apiClient.fetchAddressUtxos(senderAccount.address, _currentNetwork.value)
            if (live.isNotEmpty()) {
                _accountUtxos.update { it + (senderAccount.id to live) }
            }
            live
        } else {
            accountUtxosList
        }

        val selectedUtxos = if (manualUtxos != null && manualUtxos.isNotEmpty()) {
            val selectedSum = manualUtxos.sumOf { it.amountSompi }
            if (selectedSum < totalDebit) {
                throw IllegalArgumentException("Selected UTXOs sum is insufficient. Selected: ${KaspaUtils.formatSompi(selectedSum)}, Required: ${KaspaUtils.formatSompi(totalDebit)}")
            }
            manualUtxos
        } else if (availableUtxos.isNotEmpty()) {
            val selected = mutableListOf<UtxoEntry>()
            var accumulated = 0L
            for (u in availableUtxos) {
                selected.add(u)
                accumulated += u.amountSompi
                if (accumulated >= totalDebit) break
            }
            if (accumulated < totalDebit) {
                throw IllegalStateException("Insufficient confirmed UTXOs on Kaspa ${_currentNetwork.value.displayName}. Available: ${KaspaUtils.formatSompi(accumulated)}, Required: ${KaspaUtils.formatSompi(totalDebit)}")
            }
            selected
        } else {
            throw IllegalStateException("No confirmed UTXOs found for address ${senderAccount.address} on Kaspa ${_currentNetwork.value.displayName}. Please fund this address before sending.")
        }

        // Cryptographically sign transaction using BIP340 Schnorr and Kaspa Blake2b Sighash
        val (signedTxJson, txId) = KaspaSigner.createAndSignTransaction(
            seed = seed,
            accountIndex = senderAccount.accountIndex,
            inputs = selectedUtxos,
            recipientAddress = recipientAddress,
            amountSompi = amountSompi,
            feeSompi = feeSompi,
            changeAddress = changeAddress,
            network = _currentNetwork.value
        )

        // Broadcast authentic cryptographically signed transaction to Kaspa network
        val (broadcastSuccess, responseMsg) = apiClient.broadcastTransaction(signedTxJson, _currentNetwork.value)
        Log.i("KaspaWalletRepository", "Broadcast result: $broadcastSuccess ($responseMsg)")

        if (!broadcastSuccess) {
            throw IllegalStateException("Transaction broadcast rejected by Kaspa network: $responseMsg")
        }

        val finalTxId = if (responseMsg.length == 64 && !responseMsg.contains(" ")) responseMsg else txId

        val newSenderBalance = senderAccount.balanceSompi - totalDebit
        database.accountDao().updateBalance(senderAccount.id, newSenderBalance)

        val currentDaa = _blockDagInfo.value.virtualDaaScore + 1

        // Update local UTXOs immediately: remove spent inputs and add change UTXO if any
        val totalInput = selectedUtxos.sumOf { it.amountSompi }
        val changeAmount = totalInput - totalDebit
        val updatedUtxos = availableUtxos.filterNot { selectedUtxos.contains(it) }.toMutableList()
        if (changeAmount > 0) {
            updatedUtxos.add(
                UtxoEntry(
                    outpointTxId = finalTxId,
                    outpointIndex = 1,
                    amountSompi = changeAmount,
                    scriptPublicKey = KaspaCrypto.decodeAddressToScriptPublicKey(senderAccount.address),
                    blockDaaScore = currentDaa,
                    isCoinbase = false
                )
            )
        }
        _accountUtxos.update { current ->
            current + (senderAccount.id to updatedUtxos)
        }

        val tx = TransactionEntity(
            id = finalTxId,
            walletId = senderAccount.walletId,
            accountId = senderAccount.id,
            txType = TransactionType.SEND,
            amountSompi = amountSompi,
            feeSompi = feeSompi,
            senderAddress = senderAccount.address,
            recipientAddress = recipientAddress,
            timestamp = System.currentTimeMillis(),
            daaScore = currentDaa,
            status = TransactionStatus.PENDING,
            note = note
        )
        database.transactionDao().insertTransaction(tx)

        // Re-sync on-chain balance after broadcast
        repositoryScope.launch {
            delay(3000)
            syncAccountOnChain(senderAccount.id)
        }

        tx
    }

    suspend fun transferBetweenAccounts(
        sourceAccount: AccountEntity,
        targetAccount: AccountEntity,
        amountSompi: Long,
        feeSompi: Long,
        note: String
    ): TransactionEntity = withContext(Dispatchers.IO) {
        val totalDebit = amountSompi + feeSompi
        if (sourceAccount.balanceSompi < totalDebit) {
            throw IllegalArgumentException("Insufficient balance in source account")
        }

        database.accountDao().updateBalance(sourceAccount.id, sourceAccount.balanceSompi - totalDebit)
        database.accountDao().updateBalance(targetAccount.id, targetAccount.balanceSompi + amountSompi)

        val txId = KaspaUtils.generateTxId()
        val currentDaa = _blockDagInfo.value.virtualDaaScore + 1

        val tx = TransactionEntity(
            id = txId,
            walletId = sourceAccount.walletId,
            accountId = sourceAccount.id,
            txType = TransactionType.TRANSFER,
            amountSompi = amountSompi,
            feeSompi = feeSompi,
            senderAddress = sourceAccount.address,
            recipientAddress = targetAccount.address,
            timestamp = System.currentTimeMillis(),
            daaScore = currentDaa,
            status = TransactionStatus.CONFIRMED,
            note = if (note.isBlank()) "Transfer to ${targetAccount.name}" else note
        )
        database.transactionDao().insertTransaction(tx)
        tx
    }

    suspend fun compoundAccountUtxos(account: AccountEntity): TransactionEntity = withContext(Dispatchers.IO) {
        val currentUtxos = _accountUtxos.value[account.id] ?: emptyList()
        if (currentUtxos.isEmpty()) {
            throw IllegalStateException("No unspent outputs (UTXOs) available to compound for address ${KaspaUtils.truncateAddress(account.address)}")
        }
        if (currentUtxos.size <= 1) {
            throw IllegalStateException("UTXOs are already consolidated for address ${KaspaUtils.truncateAddress(account.address)}")
        }

        val wallet = database.walletDao().getWalletById(account.walletId)
            ?: throw IllegalStateException("Wallet not found")
        val words = wallet.encryptedMnemonic.split(" ")
        if (words.size < 12) {
            throw IllegalStateException("Invalid wallet seed words")
        }
        val seed = KaspaCrypto.mnemonicToSeed(words)
        val utxosToCompound = currentUtxos

        val totalInput = utxosToCompound.sumOf { it.amountSompi }
        val mass = KaspaSigner.calculateTransactionMass(utxosToCompound.size, 1)
        val feeSompi = KaspaSigner.calculateMinimumFeeSompi(mass)

        if (totalInput <= feeSompi) {
            throw IllegalStateException("Balance too low ($totalInput Sompi) to cover consensus network fee ($feeSompi Sompi)")
        }

        val totalAmount = totalInput - feeSompi

        val (signedTxJson, txId) = KaspaSigner.createAndSignTransaction(
            seed = seed,
            accountIndex = account.accountIndex,
            inputs = utxosToCompound,
            recipientAddress = account.address,
            amountSompi = totalAmount,
            feeSompi = feeSompi,
            changeAddress = account.address,
            network = _currentNetwork.value
        )

        val currentDaa = _blockDagInfo.value.virtualDaaScore + 1
        val consolidatedUtxo = UtxoEntry(
            outpointTxId = txId,
            outpointIndex = 0,
            amountSompi = totalAmount,
            scriptPublicKey = KaspaCrypto.decodeAddressToScriptPublicKey(account.address),
            blockDaaScore = currentDaa,
            isCoinbase = false
        )

        _accountUtxos.update { current ->
            current + (account.id to listOf(consolidatedUtxo))
        }

        database.accountDao().updateBalance(account.id, totalAmount)

        val tx = TransactionEntity(
            id = txId,
            walletId = account.walletId,
            accountId = account.id,
            txType = TransactionType.COMPOUND,
            amountSompi = totalAmount,
            feeSompi = feeSompi,
            senderAddress = account.address,
            recipientAddress = account.address,
            timestamp = System.currentTimeMillis(),
            daaScore = currentDaa,
            status = TransactionStatus.CONFIRMED,
            note = "Consolidated ${utxosToCompound.size} UTXOs • Mass: $mass grams"
        )
        database.transactionDao().insertTransaction(tx)

        if (signedTxJson.isNotEmpty()) {
            val (broadcastSuccess, responseMsg) = apiClient.broadcastTransaction(signedTxJson, _currentNetwork.value)
            Log.i("KaspaWalletRepository", "Compound broadcast result: $broadcastSuccess ($responseMsg)")
        }

        tx
    }

    suspend fun addContact(name: String, address: String, note: String): ContactEntity = withContext(Dispatchers.IO) {
        val contact = ContactEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            address = address,
            note = note,
            createdAt = System.currentTimeMillis()
        )
        database.contactDao().insertContact(contact)
        contact
    }

    suspend fun deleteContact(contact: ContactEntity) = withContext(Dispatchers.IO) {
        database.contactDao().deleteContact(contact)
    }

    suspend fun deleteWallet(walletId: String) = withContext(Dispatchers.IO) {
        database.transactionDao().deleteTransactionsForWallet(walletId)
        database.accountDao().deleteAccountsForWallet(walletId)
        database.walletDao().deleteWallet(walletId)

        if (_activeWalletId.value == walletId) {
            _activeWalletId.value = null
            _activeAccountId.value = null
        }
    }
}
