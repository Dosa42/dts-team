#include "client_session.h"

#include <algorithm>
#include <cctype>
#include <utility>

namespace client {

namespace {
constexpr int kPingIntervalSec = 30;
constexpr int kReconnectMinMs  = 1000;
constexpr int kReconnectMaxMs  = 10000;
constexpr size_t kLogPreview   = 200;

std::string trim(const std::string& s) {
    auto notSpace = [](unsigned char c) { return !std::isspace(c); };
    auto b = std::find_if(s.begin(), s.end(), notSpace);
    auto e = std::find_if(s.rbegin(), s.rend(), notSpace).base();
    return b < e ? std::string(b, e) : std::string();
}

bool startsWith(const std::string& s, const char* p) {
    return s.rfind(p, 0) == 0;
}
}  // namespace

ClientSession::ClientSession(std::string caFilePath, LogFn logFn)
    : caFile_(std::move(caFilePath)), log_(std::move(logFn)) {
    ix::SocketTLSOptions tls;
    tls.caFile = caFile_;                       // Android'de "SYSTEM" calismaz, dosya veriyoruz
    ws_.setTLSOptions(tls);

    ws_.setExtraHeaders({{"ngrok-skip-browser-warning", "1"}});
    ws_.setPingInterval(kPingIntervalSec);
    ws_.setMinWaitBetweenReconnectionRetries(kReconnectMinMs);
    ws_.setMaxWaitBetweenReconnectionRetries(kReconnectMaxMs);

    ws_.setOnMessageCallback([this](const ix::WebSocketMessagePtr& msg) {
        switch (msg->type) {
            case ix::WebSocketMessageType::Open:
                log("open: " + msg->openInfo.uri);
                break;
            case ix::WebSocketMessageType::Close:
                log("close: " + std::to_string(msg->closeInfo.code) + " " + msg->closeInfo.reason);
                break;
            case ix::WebSocketMessageType::Error:
                log("error: " + msg->errorInfo.reason +
                    " (http " + std::to_string(msg->errorInfo.http_status) + ")");
                break;
            case ix::WebSocketMessageType::Message:
                // admin'in host ettigi icerik burada acilacak.
                log("message " + std::to_string(msg->str.size()) + "B: " +
                    msg->str.substr(0, kLogPreview));
                break;
            default:   // Ping, Pong, Fragment
                break;
        }
    });
}

ClientSession::~ClientSession() {
    ws_.stop();
}

std::string ClientSession::normalizeUrl(const std::string& userInput) {
    std::string s = trim(userInput);
    if (s.empty()) return "";
    if (startsWith(s, "https://"))      s = "wss://" + s.substr(8);
    else if (startsWith(s, "http://"))  s = "ws://" + s.substr(7);
    else if (!startsWith(s, "wss://") && !startsWith(s, "ws://")) s = "wss://" + s;
    return s;
}

bool ClientSession::connect(const std::string& userInput) {
    const std::string url = normalizeUrl(userInput);
    if (url.empty()) {
        log("connect: bos adres");
        return false;
    }
    ws_.stop();
    ws_.setUrl(url);
    ws_.start();
    log("connecting: " + url);
    return true;
}

void ClientSession::disconnect() {
    ws_.stop();
    log("disconnected");
}

void ClientSession::log(const std::string& s) const {
    if (log_) log_(s);
}

}  // namespace client
