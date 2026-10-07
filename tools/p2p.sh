#!/usr/bin/env bash
# Query the Binance P2P ad book for USDT/LKR over Bank Transfer (Sri Lanka).
#
#   ./p2p.sh [SELL|BUY] [rows]
#
# SELL = ads you can sell USDT into   (advertisers are buying)
# BUY  = ads you can buy USDT from    (advertisers are selling)
#
# Request tradeType is *your* action; the adv.tradeType in the response is the
# advertiser's, and is therefore inverted.
set -euo pipefail

TRADE="${1:-SELL}"
ROWS="${2:-10}"
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36'

curl -s 'https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search' \
  -H 'Content-Type: application/json' \
  -H "User-Agent: $UA" \
  -d "{\"fiat\":\"LKR\",\"page\":1,\"rows\":$ROWS,\"tradeType\":\"$TRADE\",\"asset\":\"USDT\",\"countries\":[],\"proMerchantAds\":false,\"shieldMerchantAds\":false,\"filterType\":\"all\",\"periods\":[],\"additionalKycVerifyFilter\":0,\"publisherType\":null,\"payTypes\":[\"BankSriLanka\"],\"classifies\":[\"mass\",\"profession\",\"fiat_trade\"]}" \
| jq -r '
  if .code != "000000" then
    "API error: \(.code) \(.message // "")\n" | halt_error(1)
  else . end
  | ["PRICE","AVAILABLE","LIMIT_LKR","ADVERTISER","ORDERS","COMPL%","POS%","PAY"],
    (.data[] | [
      .adv.price,
      (.adv.tradableQuantity | tonumber | floor | tostring) + " USDT",
      (.adv.minSingleTransAmount | tonumber | floor | tostring) + "-"
        + (.adv.dynamicMaxSingleTransAmount | tonumber | floor | tostring),
      .advertiser.nickName,
      (.advertiser.monthOrderCount | tostring),
      ((.advertiser.monthFinishRate * 100) | floor | tostring),
      ((.advertiser.positiveRate * 100) | floor | tostring),
      (.adv.payTimeLimit | tostring) + "m"
    ]) | @tsv' | column -t -s "$(printf '\t')"
