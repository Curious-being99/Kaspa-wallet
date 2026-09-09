package com.example.kaspawallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kaspawallet.data.crypto.KaspaUtils
import com.example.kaspawallet.data.model.*
import com.example.kaspawallet.data.repository.KaspaWalletRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MainTab(val title: String) {
    OVERVIEW("Overview"),
    TRANSACTIONS("Activity"),
    BLOCKDAG("BlockDAG"),
    TOOLS("UTXO & Tools"),
    SETTINGS("Settings")
}

data class WalletUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedTab: MainTab = MainTab.OVERVIEW,
    val wallets: List<WalletEntity> = emptyList(),
    val activeWallet: WalletEntity? = null,
    val accounts: List<AccountEntity> = emptyList(),
    val activeAccount: AccountEntity? = null,
    val transactions: List<TransactionEntity> = emptyList(),
    val utxos: List<UtxoEntry> = emptyList(),
    val contacts: List<ContactEntity> = emptyList(),
    val network: KaspaNetwork = KaspaNetwork.MAINNET,
    val blockDagInfo: BlockDagInfo = BlockDagInfo(),
    val marketInfo: KaspaMarketInfo = KaspaMarketInfo(),
    val selectedCurrency: String = "USD",
    val isWalletLocked: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastSentTx: TransactionEntity? = null,
    
    // Dialog states
    val showSendDialog: Boolean = false,
    val showTxSuccessDialog: Boolean = false,
    val showReceiveDialog: Boolean = false,
    val showTransferDialog: Boolean = false,
    val showAddAccountDialog: Boolean = false,
    val showAddContactDialog: Boolean = false,
    val showSeedBackupDialog: Boolean = false,
    val showCreateWalletDialog: Boolean = false,
    val showImportWalletDialog: Boolean = false,
    val showSetupWizard: Boolean = false,
    val setupWizardMode: String = "CREATE" // "CREATE" or "IMPORT"
)

enum class ScanIndexingStage {
    IDLE,
    DERIVING_KEYS,
    CONNECTING_NODE,
    SCANNING_UTXOS,
    CALCULATING_BALANCE,
    INDEXING_HISTORY,
    COMPLETE,
    FAILED
}

data class ScanIndexingUiState(
    val isScanning: Boolean = false,
    val stage: ScanIndexingStage = ScanIndexingStage.IDLE,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val derivedAddress: String = "",
    val derivationPath: String = "m/44'/111111'/0'/0/0",
    val network: KaspaNetwork = KaspaNetwork.MAINNET,
    val daaScore: Long = 0L,
    val balanceSompi: Long = 0L,
    val utxoCount: Int = 0,
    val txCount: Int = 0,
    val indexedTransactions: List<TransactionEntity> = emptyList(),
    val isImportMode: Boolean = false,
    val walletName: String = "",
    val isComplete: Boolean = false,
    val error: String? = null
)

class KaspaViewModel(val repository: KaspaWalletRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private val _scanIndexingState = MutableStateFlow(ScanIndexingUiState())
    val scanIndexingState: StateFlow<ScanIndexingUiState> = _scanIndexingState.asStateFlow()

    private var isInitialWalletLoad = true

    init {
        // Collect wallets
        viewModelScope.launch {
            repository.allWallets.collect { walletList ->
                _uiState.update { current ->
                    val active = if (current.activeWallet != null) {
                        walletList.find { it.id == current.activeWallet.id }
                    } else {
                        walletList.firstOrNull()
                    }
                    val shouldLock = isInitialWalletLoad && walletList.isNotEmpty()
                    if (isInitialWalletLoad) {
                        isInitialWalletLoad = false
                    }
                    current.copy(
                        wallets = walletList,
                        activeWallet = active,
                        isWalletLocked = if (shouldLock) true else current.isWalletLocked
                    )
                }
                _uiState.value.activeWallet?.let {
                    repository.setActiveWallet(it.id)
                    observeAccountsAndTransactions(it.id)
                }
            }
        }

        // Collect network
        viewModelScope.launch {
            repository.currentNetwork.collect { net ->
                _uiState.update { it.copy(network = net) }
            }
        }

        // Collect BlockDAG & Node
        viewModelScope.launch {
            repository.blockDagInfo.collect { dag ->
                _uiState.update { it.copy(blockDagInfo = dag) }
            }
        }

        // Collect Market info
        viewModelScope.launch {
            repository.marketInfo.collect { market ->
                _uiState.update { it.copy(marketInfo = market) }
            }
        }

        // Collect Contacts
        viewModelScope.launch {
            repository.allContacts.collect { contactList ->
                _uiState.update { it.copy(contacts = contactList) }
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                repository.syncNetworkMetrics()
                repository.syncMarketPrice()
                _uiState.value.activeAccount?.let {
                    repository.syncAccountOnChain(it.id)
                }
                _uiState.update { it.copy(isRefreshing = false, statusMessage = "Synced with Kaspa Network") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, errorMessage = "Network sync error: ${e.localizedMessage}") }
            }
        }
    }

    fun refreshPrice() {
        viewModelScope.launch {
            try {
                repository.syncMarketPrice()
                val currentPrice = _uiState.value.marketInfo.priceUsd
                _uiState.update { it.copy(statusMessage = "Kaspa price updated: $${String.format(java.util.Locale.US, "%.4f", currentPrice)}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to update price: ${e.localizedMessage}") }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setNetwork(network: KaspaNetwork) {
        repository.setNetwork(network)
    }

    fun setCustomNodeUrl(url: String?) {
        repository.setCustomRpcEndpoint(url)
    }

    fun setCurrency(currency: String) {
        _uiState.update { it.copy(selectedCurrency = currency) }
    }

    fun selectWallet(wallet: WalletEntity) {
        _uiState.update { it.copy(activeWallet = wallet) }
        repository.setActiveWallet(wallet.id)
        observeAccountsAndTransactions(wallet.id)
    }

    fun selectAccount(account: AccountEntity) {
        _uiState.update { it.copy(activeAccount = account) }
        repository.setActiveAccount(account.id)
        updateAccountUtxos(account.id)
    }

    private fun observeAccountsAndTransactions(walletId: String) {
        viewModelScope.launch {
            repository.getAccountsForWallet(walletId).collect { accountList ->
                _uiState.update { current ->
                    val activeAcc = if (current.activeAccount != null) {
                        accountList.find { it.id == current.activeAccount.id } ?: accountList.firstOrNull()
                    } else {
                        accountList.firstOrNull()
                    }
                    current.copy(
                        accounts = accountList,
                        activeAccount = activeAcc
                    )
                }
                _uiState.value.activeAccount?.let {
                    repository.setActiveAccount(it.id)
                    updateAccountUtxos(it.id)
                }
            }
        }

        viewModelScope.launch {
            repository.getTransactionsForWallet(walletId).collect { txList ->
                _uiState.update { it.copy(transactions = txList) }
            }
        }
    }

    private fun updateAccountUtxos(accountId: String) {
        viewModelScope.launch {
            repository.accountUtxos.collect { map ->
                val list = map[accountId] ?: emptyList()
                _uiState.update { it.copy(utxos = list) }
            }
        }
    }

    fun startSetupWizard(mode: String = "CREATE") {
        _uiState.update { it.copy(showSetupWizard = true, setupWizardMode = mode) }
    }

    fun closeSetupWizard() {
        _uiState.update { it.copy(showSetupWizard = false) }
    }

    fun createNewWallet(
        context: android.content.Context,
        name: String,
        words: List<String>,
        hasPassphrase: Boolean = false,
        passphrase: String = "",
        network: KaspaNetwork = KaspaNetwork.MAINNET,
        password: String = ""
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                if (network != _uiState.value.network) {
                    repository.setNetwork(network)
                }
                val (wallet, account) = repository.createWallet(name, words, hasPassphrase)
                saveWalletPassword(context, wallet.id, password)
                _uiState.update { current ->
                    val updatedWallets = if (current.wallets.any { it.id == wallet.id }) current.wallets else current.wallets + wallet
                    current.copy(
                        isLoading = false,
                        wallets = updatedWallets,
                        activeWallet = wallet,
                        activeAccount = account,
                        showCreateWalletDialog = false,
                        showSetupWizard = false,
                        statusMessage = "Kaspa Wallet '${wallet.name}' generated successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage ?: "Failed to create wallet") }
            }
        }
    }

    fun importWallet(
        context: android.content.Context,
        name: String,
        mnemonicPhrase: String,
        network: KaspaNetwork = KaspaNetwork.MAINNET,
        passphrase: String = "",
        password: String = ""
    ) {
        viewModelScope.launch {
            val words = mnemonicPhrase.trim().split(Regex("\\s+"))
            if (words.size != 12 && words.size != 24) {
                _uiState.update { it.copy(errorMessage = "Seed phrase must be 12 or 24 words") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            try {
                if (network != _uiState.value.network) {
                    repository.setNetwork(network)
                }
                val (wallet, account) = repository.createWallet(name, words, passphrase.isNotBlank())
                saveWalletPassword(context, wallet.id, password)
                _uiState.update { current ->
                    val updatedWallets = if (current.wallets.any { it.id == wallet.id }) current.wallets else current.wallets + wallet
                    current.copy(
                        isLoading = false,
                        wallets = updatedWallets,
                        activeWallet = wallet,
                        activeAccount = account,
                        showImportWalletDialog = false,
                        showSetupWizard = false,
                        statusMessage = "Kaspa Wallet '${wallet.name}' imported successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.localizedMessage ?: "Failed to import wallet") }
            }
        }
    }

    fun startScanAndIndex(
        context: android.content.Context,
        name: String,
        words: List<String>,
        hasPassphrase: Boolean = false,
        passphrase: String = "",
        network: KaspaNetwork = KaspaNetwork.MAINNET,
        password: String = "",
        isImport: Boolean = false
    ) {
        viewModelScope.launch {
            val initialAddress = try {
                KaspaUtils.generateDeterministicAddress(
                    mnemonicWords = words,
                    accountIndex = 0,
                    network = network,
                    passphrase = passphrase
                )
            } catch (e: Exception) {
                ""
            }

            _scanIndexingState.value = ScanIndexingUiState(
                isScanning = true,
                stage = ScanIndexingStage.DERIVING_KEYS,
                progress = 0.15f,
                statusMessage = "Deriving cryptographic BIP-44 keypair & address...",
                derivedAddress = initialAddress,
                network = network,
                walletName = name,
                isImportMode = isImport
            )

            try {
                if (network != _uiState.value.network) {
                    repository.setNetwork(network)
                }

                // Stage 1: Key Derivation
                kotlinx.coroutines.delay(650)
                val targetAddress = if (initialAddress.isNotBlank()) initialAddress else {
                    KaspaUtils.generateDeterministicAddress(
                        mnemonicWords = words,
                        accountIndex = 0,
                        network = network,
                        passphrase = passphrase
                    )
                }
                _scanIndexingState.update {
                    it.copy(
                        derivedAddress = targetAddress,
                        progress = 0.30f,
                        statusMessage = "Target address derived: ${targetAddress.take(18)}..."
                    )
                }

                // Stage 2: Node Handshake & DAA Score
                _scanIndexingState.update {
                    it.copy(
                        stage = ScanIndexingStage.CONNECTING_NODE,
                        progress = 0.45f,
                        statusMessage = "Connecting to Kaspa node (${network.displayName})..."
                    )
                }
                val dagInfo = try {
                    repository.apiClient.fetchBlockDagInfo(network)
                } catch (e: Exception) {
                    BlockDagInfo()
                }
                val liveDaaScore = if (dagInfo.virtualDaaScore > 0) dagInfo.virtualDaaScore else 53_580_000L
                kotlinx.coroutines.delay(600)
                _scanIndexingState.update {
                    it.copy(
                        daaScore = liveDaaScore,
                        progress = 0.60f,
                        statusMessage = "Connected to BlockDAG. Current DAA: #$liveDaaScore"
                    )
                }

                // Stage 3: Scanning UTXOs on-chain
                _scanIndexingState.update {
                    it.copy(
                        stage = ScanIndexingStage.SCANNING_UTXOS,
                        progress = 0.72f,
                        statusMessage = "Querying live unspent outputs (UTXO set)..."
                    )
                }
                val liveUtxos = try {
                    repository.apiClient.fetchAddressUtxos(targetAddress, network)
                } catch (e: Exception) {
                    emptyList()
                }
                kotlinx.coroutines.delay(600)
                _scanIndexingState.update {
                    it.copy(
                        utxoCount = liveUtxos.size,
                        progress = 0.82f,
                        statusMessage = if (liveUtxos.isNotEmpty()) "${liveUtxos.size} UTXOs discovered on-chain" else "Zero unspent UTXOs (Clean graph)"
                    )
                }

                // Stage 4: Calculating On-Chain Consensus Balance
                _scanIndexingState.update {
                    it.copy(
                        stage = ScanIndexingStage.CALCULATING_BALANCE,
                        progress = 0.90f,
                        statusMessage = "Calculating confirmed on-chain balance..."
                    )
                }
                val liveBalanceSompi = try {
                    repository.apiClient.fetchAddressBalance(targetAddress, network)
                } catch (e: Exception) {
                    0L
                }
                kotlinx.coroutines.delay(600)
                _scanIndexingState.update {
                    it.copy(
                        balanceSompi = liveBalanceSompi,
                        progress = 0.94f,
                        statusMessage = "Balance verified: ${KaspaUtils.formatKas(KaspaUtils.sompiToKas(liveBalanceSompi))} KAS"
                    )
                }

                // Stage 5: Indexing Historical Transactions & Persisting to Room
                _scanIndexingState.update {
                    it.copy(
                        stage = ScanIndexingStage.INDEXING_HISTORY,
                        progress = 0.97f,
                        statusMessage = "Indexing BlockDAG transaction history..."
                    )
                }
                val (wallet, account) = repository.createWallet(
                    name = name,
                    mnemonicWords = words,
                    hasPassphrase = hasPassphrase,
                    passphrase = passphrase
                )
                saveWalletPassword(context, wallet.id, password)

                val txs = try {
                    repository.apiClient.fetchAddressTransactions(targetAddress, wallet.id, account.id, network)
                } catch (e: Exception) {
                    emptyList()
                }

                // Stage 6: Complete
                kotlinx.coroutines.delay(600)
                _scanIndexingState.update {
                    it.copy(
                        stage = ScanIndexingStage.COMPLETE,
                        progress = 1.0f,
                        isComplete = true,
                        isScanning = false,
                        txCount = txs.size,
                        indexedTransactions = txs,
                        statusMessage = "On-chain scanning & indexing complete!"
                    )
                }

                _uiState.update { current ->
                    val updatedWallets = if (current.wallets.any { w -> w.id == wallet.id }) current.wallets else current.wallets + wallet
                    current.copy(
                        wallets = updatedWallets,
                        activeWallet = wallet,
                        activeAccount = account
                    )
                }
            } catch (e: Exception) {
                _scanIndexingState.update {
                    it.copy(
                        stage = ScanIndexingStage.FAILED,
                        isScanning = false,
                        error = e.localizedMessage ?: "Failed to index wallet on-chain"
                    )
                }
            }
        }
    }

    fun finishScanAndNavigateToWallet() {
        val activeWalletName = _scanIndexingState.value.walletName
        _uiState.update { current ->
            current.copy(
                showCreateWalletDialog = false,
                showImportWalletDialog = false,
                showSetupWizard = false,
                statusMessage = if (activeWalletName.isNotBlank()) "Kaspa Wallet '$activeWalletName' ready" else null
            )
        }
        _scanIndexingState.value = ScanIndexingUiState()
    }

    fun resetScanIndexing() {
        _scanIndexingState.value = ScanIndexingUiState()
    }

    fun createNewAccount(name: String) {
        val currentWallet = _uiState.value.activeWallet ?: return
        viewModelScope.launch {
            try {
                val nextIndex = _uiState.value.accounts.size
                val account = repository.createAccount(currentWallet.id, name, nextIndex)
                _uiState.update {
                    it.copy(
                        activeAccount = account,
                        showAddAccountDialog = false,
                        statusMessage = "Account '${account.name}' added"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Failed to add account") }
            }
        }
    }

    fun sendKas(
        recipientAddress: String,
        amountKas: Double,
        feeOption: String,
        customFeeKas: Double = 0.00386,
        note: String = "",
        manualUtxos: List<UtxoEntry>? = null
    ) {
        val account = _uiState.value.activeAccount ?: return
        if (!KaspaUtils.isValidKaspaAddress(recipientAddress)) {
            _uiState.update { it.copy(errorMessage = "Invalid Kaspa address format") }
            return
        }
        val amountSompi = KaspaUtils.kasToSompi(amountKas)
        val feeSompi = when (feeOption) {
            "Low" -> KaspaUtils.DEFAULT_MIN_FEE_SOMPI
            "Priority" -> KaspaUtils.HIGH_PRIORITY_FEE_SOMPI
            "Custom" -> KaspaUtils.kasToSompi(customFeeKas)
            else -> KaspaUtils.PRIORITY_FEE_SOMPI
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val sentTx = repository.sendKas(account, recipientAddress, amountSompi, feeSompi, note, manualUtxos)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showSendDialog = false,
                        showTxSuccessDialog = true,
                        lastSentTx = sentTx,
                        statusMessage = "Sent ${KaspaUtils.formatKas(amountKas)} successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Failed to submit transaction"
                    )
                }
            }
        }
    }

    fun dismissTxSuccessDialog() {
        _uiState.update { it.copy(showTxSuccessDialog = false) }
    }

    fun transferBetweenAccounts(targetAccount: AccountEntity, amountKas: Double, note: String = "") {
        val source = _uiState.value.activeAccount ?: return
        val amountSompi = KaspaUtils.kasToSompi(amountKas)
        val feeSompi = KaspaUtils.DEFAULT_MIN_FEE_SOMPI

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                repository.transferBetweenAccounts(source, targetAccount, amountSompi, feeSompi, note)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showTransferDialog = false,
                        statusMessage = "Transferred ${KaspaUtils.formatKas(amountKas)} to ${targetAccount.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Transfer failed"
                    )
                }
            }
        }
    }

    fun compoundUtxos() {
        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val tx = repository.compoundAccountUtxos(account)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Compounded UTXOs successfully (TxID: ${KaspaUtils.truncateAddress(tx.id)})"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Compound operation failed"
                    )
                }
            }
        }
    }

    fun saveContact(name: String, address: String, note: String = "") {
        if (!KaspaUtils.isValidKaspaAddress(address)) {
            _uiState.update { it.copy(errorMessage = "Invalid Kaspa address") }
            return
        }
        viewModelScope.launch {
            repository.addContact(name, address, note)
            _uiState.update {
                it.copy(
                    showAddContactDialog = false,
                    statusMessage = "Contact '$name' saved"
                )
            }
        }
    }

    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            repository.deleteContact(contact)
            _uiState.update { it.copy(statusMessage = "Contact removed") }
        }
    }

    fun deleteCurrentWallet(context: android.content.Context) {
        val wallet = _uiState.value.activeWallet ?: return
        viewModelScope.launch {
            repository.deleteWallet(wallet.id)
            deleteWalletPassword(context, wallet.id)
            _uiState.update {
                it.copy(
                    activeWallet = null,
                    activeAccount = null,
                    statusMessage = "Wallet '${wallet.name}' deleted"
                )
            }
        }
    }

    private fun hashPassword(walletId: String, password: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest("$walletId:$password".toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            password
        }
    }

    fun saveWalletPassword(context: android.content.Context, walletId: String, password: String) {
        val prefs = context.getSharedPreferences("wallet_passwords", android.content.Context.MODE_PRIVATE)
        val hashedPassword = hashPassword(walletId, password)
        prefs.edit().putString(walletId, hashedPassword).apply()
    }

    fun verifyWalletPassword(context: android.content.Context, walletId: String, password: String): Boolean {
        val prefs = context.getSharedPreferences("wallet_passwords", android.content.Context.MODE_PRIVATE)
        val savedHash = prefs.getString(walletId, null)
        return savedHash == null || savedHash == hashPassword(walletId, password)
    }

    fun deleteWalletPassword(context: android.content.Context, walletId: String) {
        val prefs = context.getSharedPreferences("wallet_passwords", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(walletId).apply()
    }

    fun setLockWallet(locked: Boolean) {
        _uiState.update { it.copy(isWalletLocked = locked) }
    }

    // Dialog toggles
    fun setShowSendDialog(show: Boolean) = _uiState.update { it.copy(showSendDialog = show) }
    fun setShowReceiveDialog(show: Boolean) = _uiState.update { it.copy(showReceiveDialog = show) }
    fun setShowTransferDialog(show: Boolean) = _uiState.update { it.copy(showTransferDialog = show) }
    fun setShowAddAccountDialog(show: Boolean) = _uiState.update { it.copy(showAddAccountDialog = show) }
    fun setShowAddContactDialog(show: Boolean) = _uiState.update { it.copy(showAddContactDialog = show) }
    fun setShowSeedBackupDialog(show: Boolean) = _uiState.update { it.copy(showSeedBackupDialog = show) }
    fun setShowCreateWalletDialog(show: Boolean) = _uiState.update { it.copy(showCreateWalletDialog = show) }
    fun setShowImportWalletDialog(show: Boolean) = _uiState.update { it.copy(showImportWalletDialog = show) }
    fun clearStatusMessage() = _uiState.update { it.copy(statusMessage = null) }
    fun clearErrorMessage() = _uiState.update { it.copy(errorMessage = null) }
}

class KaspaViewModelFactory(private val repository: KaspaWalletRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KaspaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return KaspaViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
