# SentinelStream: AI-Driven Cross-Border Fraud Prevention
> **2026 AWS Serverless Developer Challenge - Showcase Project**

## 🌟 Overview
SentinelStream is a high-concurrency, cloud-native fraud detection system designed for global e-commerce platforms. It leverages **Java 21 Virtual Threads** and **Amazon Bedrock (Claude 3.5 Haiku)** to analyze cross-border transactions in real-time.

### Key Value Propositions:
*   **Explainable AI**: Not just a risk score, but a detailed reason for every decision.
*   **Ultra-Low Latency**: Optimized for global scale using Spring Boot 3 & AWS SDK 2.x.
*   **Security First**: Designed to detect common fraud patterns like IP-location mismatch and anomalous spending.

---

## 🛠️ Technical Stack
- **Backend**: Java 21, Spring Boot 3.3+, Spring Data JPA
- **Database**: PostgreSQL (Dockerized)
- **AI/ML**: Amazon Bedrock (Model: `us.anthropic.claude-3-5-haiku-20241022-v1:0`)
- **Infrastructure**: AWS SDK for Java 2.x, Docker
- **Monitoring**: SLF4J + Logback for observability

---

## 🏗️ System Architecture
1. **API Layer**: Receives global order data (JSON).
2. **AI Reasoning Layer**: Sends transaction features to Amazon Bedrock for real-time risk evaluation.
3. **Decision Engine**: Automatically approves or rejects orders based on AI risk scores (>0.7).
4. **Persistence**: Stores transaction history and AI reasons for auditing.

---

## 🚀 Getting Started

### Prerequisites
- JDK 21
- Docker Desktop
- AWS Credentials (with Bedrock access)

### Installation
1. Clone the repo:
   ```bash
   git clone https://github.com/Iris34382940/SentinelStream-Fraud-Prevention.git
