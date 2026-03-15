# 🛡️ SentinelStream - AI-Driven Fraud Prevention System

這是一個基於雲端原生架構開發的即時詐騙偵測系統，專為處理高併發的電商交易場景而設計。

## 🚀 Live Demo API
你可以使用 Postman 發送 `POST` 請求至以下端點進行即時測試：
- **Endpoint:** `https://prwjcz06oc.execute-api.us-east-1.amazonaws.com/Prod/submit`
- **Method:** `POST`
- **Payload Example:**
  ```json
  {
      "userId": "user_suspect_125",
      "amount": 5000.0,
      "currency": "USD",
      "ipAddress": "185.225.69.1",
      "shippingCountry": "Ukraine"
  }
```
🏗️ System Architecture (Updated)
This system is built on a fully Serverless architecture to ensure high performance, scalability, and data persistence for global e-commerce transactions:
1. Amazon API Gateway: Unified entry point for global transaction data, handling RESTful routing and traffic management.
2. AWS Lambda: Core logic layer powered by Java 21 (Virtual Threads optimized). It achieves minimal resource consumption and lightning-fast response times through modern concurrency patterns.
3. Amazon Bedrock: Fraud reasoning driven by Claude 3.5 Haiku. The model analyzes transaction context in milliseconds to provide structured risk scores and detailed insights.
4. Amazon DynamoDB (New): Persistence Layer. A high-performance NoSQL table that automatically logs every fraud alert and AI-generated insight, ensuring full traceability and auditability.
5. Security (IAM): Strictly follows the "Least Privilege Principle". The Lambda function is granted only specific CRUD permissions for the designated DynamoDB table, ensuring cloud asset security.

🛠️ 技術棧 (Tech Stack)
● Language: Java 21
● Cloud: AWS (Lambda, API Gateway, IAM)
● AI Model: Anthropic Claude 3.5 Haiku via Amazon Bedrock
● Deployment: AWS SAM, Maven


