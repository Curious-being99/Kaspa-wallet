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

class KaspaViewModel(private val repository: KaspaWalletRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

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
                    current.copy(
                        wallets = walletList,
                        activeWallet = active
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
        customFeeKas: Double = 0.0001,
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
