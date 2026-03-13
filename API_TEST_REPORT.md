# 風控系統 API 測試紀錄 (2026-03-13)

本文件紀錄了風控系統對於不同風險程度訂單的處理邏輯與實際輸出。

---

## 測試案例 A：正常訂單 (Normal Order)
### 請求參數 (Postman Request)
```json
{
    "userId": "user_normal_001",
    "amount": 50.0,
    "currency": "USD",
    "ipAddress": "122.11.2.5", 
    "shippingCountry": "Taiwan"
}
```
系統回應 (Response)
```json
{
    "id": 14,
    "userId": "user_normal_001",
    "amount": 50.0,
    "currency": "USD",
    "ipAddress": "122.11.2.5",
    "shippingCountry": "Taiwan",
    "status": "APPROVED",
    "riskScore": 0.65,
    "riskReason": "Non-US IP with moderate transaction amount could indicate potential international fraud",
    "createdAt" : "2026-03-13T14:05:45.772142"
}
```
測試案例 B：疑似詐騙 (Fraud Suspect)
請求參數 (Postman Request)
```json
{
    "userId": "user_suspect_999",
    "amount": 5000.0,
    "currency": "USD",
    "ipAddress": "185.225.69.1", 
    "shippingCountry": "Ukraine"
}
```
系統回應 (Response)
```json
{
    "id": 15,
    "userId": "user_suspect_999",
    "amount": 5000.0,
    "currency": "USD",
    "ipAddress": "185.225.69.1",
    "shippingCountry": "Ukraine",
    "status": "REJECTED (AI High Risk)",
    "riskScore": 0.85,
    "riskReason": "High-value transaction to Ukraine from suspicious IP range associated with potential fraud activity",
    "createdAt": "2026-03-13T14:06:33.4383899"
}
```


