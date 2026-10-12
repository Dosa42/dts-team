#pragma once
// ClientSession: SADECE client. Verilen adrese WebSocket ile baglanir.
#include <functional>
#include <string>

#include <ixwebsocket/IXWebSocket.h>

namespace client {

class ClientSession {
public:
    using LogFn = std::function<void(const std::string&)>;

    // caFilePath: wss:// icin guvenilen CA dosyasi (PEM)
    ClientSession(std::string caFilePath, LogFn logFn);
    ~ClientSession();

    // "xxx.ngrok-free.app" | "https://..." | "wss://..." | "http://IP:8085" | "ws://IP:8085"
    // Onceki baglanti kapatilir. Bos girdi -> false.
    bool connect(const std::string& userInput);
    void disconnect();

    static std::string normalizeUrl(const std::string& userInput);

private:
    void log(const std::string& s) const;

    std::string caFile_;
    LogFn log_;
    ix::WebSocket ws_;   // en sonda: ilk o yok edilir, callback log_ kullandigi icin
};

}  // namespace client
