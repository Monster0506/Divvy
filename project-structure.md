The goal of the technical stack is to support both Android and iOS while keeping the infrastructure simple enough to build and iterate on quickly.

## Mobile -- Kotlin Multiplatform

The mobile application will use **Kotlin Multiplatform with Compose Multiplatform**, allowing us to share most of the application and UI between Android and iOS.

Android development and testing can be done through Android Studio, while iOS builds can be compiled and installed on a physical iPhone using a MacBook and Apple Developer account.

JetBrains documents running a Compose Multiplatform application directly on an iPhone here:

https://kotlinlang.org/docs/multiplatform/compose-multiplatform-create-first-app.html#run-on-a-real-ios-device

This gives us one primary mobile codebase while still allowing platform-specific functionality when necessary.

## Backend -- Kotlin + Ktor

The backend will use **Ktor**, keeping Kotlin as the primary language across both the mobile application and server.

Ktor will provide the API used by the mobile clients and handle the application's core business logic, including households, receipts, grocery lists, ownership, and balances.

## Database -- PostgreSQL

**PostgreSQL** will store household, receipt, purchase, ownership, and other application data.

We can use **pgvector** where semantic embeddings need to be stored or searched, avoiding the need for a separate vector database.

## Machine Learning -- Python

The machine-learning components will remain in **Python**, where we can easily experiment with embeddings, classification, semantic similarity, confidence calibration, and active learning.

The Kotlin backend will communicate with the Python ML service over a simple internal HTTP API.

Receipt understanding and ownership prediction will remain separate: receipt processing determines **what was purchased**, while the household model predicts **who it belongs to**.

## Deployment

The backend will run on a **4 vCPU, 8 GB RAM, 320 GB Linux server**.

The server stack will be Dockerized using **Docker Compose**:

```text
Caddy
  |
Ktor
 /  \
Python ML
   PostgreSQL + pgvector
```

Ktor will be the public application API, while the Python ML service and PostgreSQL remain internal to the Docker network.

This keeps deployment simple while giving us enough resources to run the application, database, and ML inference on a single machine.

## Stack Summary

**Mobile:** Kotlin Multiplatform + Compose Multiplatform
**Backend:** Kotlin + Ktor
**Database:** PostgreSQL + pgvector
**ML:** Python
**Deployment:** Docker Compose on a Linux server