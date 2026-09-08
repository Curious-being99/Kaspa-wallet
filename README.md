# Kaspa Android Wallet

## Description
Kaspa Android Wallet is a secure, open-source, non-custodial mobile wallet for the Kaspa (KAS) network. Built natively for Android using Kotlin and Jetpack Compose, it empowers users to safely manage their Kaspa assets, create or import 24-word seed phrases, and execute transactions directly on the Kaspa blockDAG. 

## Features & Functions
* **Non-Custodial Asset Management:** Your private keys are derived locally and never leave your device. You retain absolute control over your funds.
* **Wallet Creation & Import:** Generate a new BIP-39 secure seed phrase or restore an existing wallet seamlessly.
* **Native Kaspa Cryptography:** Fully supports Kaspa's core cryptographic standards, including UTXO management, BIP-340 Schnorr signatures, and Kaspa Sighash transaction formatting.
* **Advanced Security Layer:**
  * **Biometric Authentication:** Supports hardware-backed fingerprint and facial recognition to authorize critical wallet actions.
  * **Secure Credential Storage:** Wallet passwords are mathematically secured using SHA-256 salted hashing to prevent local extraction.
  * **R8/ProGuard Hardening:** Production releases are fully minified and obfuscated to protect binary integrity.
* **Network Synchronization:** Connects to robust Kaspa network endpoints via REST APIs to fetch balances, resolve UTXOs, and broadcast signed transactions in real time.
* **Modern UI/UX:** A highly responsive, smooth interface built entirely with Jetpack Compose following Google's Material Design 3 guidelines.

## Project Structure
The codebase strictly adheres to clean **MVVM (Model-View-ViewModel)** architecture to ensure scalable and maintainable code:

* `app/src/main/java/com/example/kaspawallet/`
  * `ui/`: Contains all visual components, Jetpack Compose screens (`MainScreen`, `WalletSetupWizard`, `TransactionsTab`), Dialogs, and the `KaspaViewModel`.
  * `data/`: The core data layer responsible for state mapping, network traffic, and cryptography.
    * `api/`: Outbound network communication (fetching UTXOs and broadcasting transactions) using OkHttp.
    * `crypto/`: Core cryptographic primitives, key derivation, and transaction signing engines.
    * `local/`: Local persistence engine using Android Room Database to cache transaction history securely.
    * `model/`: Data structures, serialization models, and domain schemas.
    * `repository/`: `KaspaWalletRepository`, acting as the single source of truth routing data between the network, local storage, and UI.

## License

```text
MIT License

Copyright (c) 2026 Kaspa Wallet Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
