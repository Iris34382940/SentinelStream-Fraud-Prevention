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
🏗️ 系統架構亮點 (Architecture Highlights)
本專案採用最現代化的 Serverless 架構，確保系統的高可用性與彈性伸縮能力：
● Java 21 (LTS): 使用最新的 Java 版本，利用其效能優化與現代語法特性。
● AWS Lambda (Pure Function): 捨棄沈重的 Spring Boot 框架，採用純 Lambda Handler 模式，將「冷啟動 (Cold Start)」時間降至最低。
● Amazon Bedrock (Claude 3.5 Haiku): 核心風險分析由 AWS 最先進的生成式 AI 模型驅動，能夠理解複雜的地理政治風險與異常行為模式。
● Amazon API Gateway: 作為系統的安全門戶，負責處理流量管控與 RESTful 路由轉發。
● AWS SAM (Serverless Application Model): 實踐「架構即代碼 (IaC)」，確保開發環境與生產環境的一致性。

🛠️ 技術棧 (Tech Stack)
● Language: Java 21
● Cloud: AWS (Lambda, API Gateway, IAM)
● AI Model: Anthropic Claude 3.5 Haiku via Amazon Bedrock
● Deployment: AWS SAM, Maven


