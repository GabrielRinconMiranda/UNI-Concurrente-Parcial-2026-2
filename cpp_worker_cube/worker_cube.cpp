#include "micro_chunks.hpp"
#include <iostream>
#include <string>
#include <vector>
#include <sstream>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>

static int g_thread_count = 4;

std::string handle_message(const std::string& line) {
    size_t p1 = line.find('|');
    if (p1 == std::string::npos) return "";
    std::string action = line.substr(0, p1);

    size_t p2 = line.find('|', p1 + 1);
    std::string task_id, payload;
    if (p2 == std::string::npos) {
        task_id = line.substr(p1 + 1);
    } else {
        task_id = line.substr(p1 + 1, p2 - (p1 + 1));
        payload = line.substr(p2 + 1);
    }

    if (action == "CONV_REQ") {
        std::vector<float> pixels;
        std::stringstream ss(payload);
        std::string token;
        while (std::getline(ss, token, ',')) {
            if (!token.empty()) pixels.push_back(std::stof(token));
        }

        auto feats = OpenAICubes::convolution_chunk(pixels, g_thread_count);
        std::stringstream out;
        for (size_t i = 0; i < feats.size(); ++i) {
            if (i > 0) out << ",";
            out << std::fixed << std::setprecision(4) << feats[i];
        }
        return "CONV_RESP|" + task_id + "|" + out.str();

    } else if (action == "MLP_REQ") {
        std::vector<float> feats;
        std::stringstream ss(payload);
        std::string token;
        while (std::getline(ss, token, ',')) {
            if (!token.empty()) feats.push_back(std::stof(token));
        }

        auto [label, conf, probs] = OpenAICubes::mlp_chunk(feats);
        std::stringstream out;
        out << label << ":" << std::fixed << std::setprecision(2) << (conf * 100.0f);
        for (float p : probs) {
            out << ";" << std::fixed << std::setprecision(4) << p;
        }
        return "MLP_RESP|" + task_id + "|" + out.str();

    } else if (action == "INFER_REQ") {
        size_t pipe = payload.find('|');
        std::string csv = (pipe != std::string::npos) ? payload.substr(pipe + 1) : payload;

        std::vector<float> pixels;
        std::stringstream ss(csv);
        std::string token;
        while (std::getline(ss, token, ',')) {
            if (!token.empty()) pixels.push_back(std::stof(token));
        }

        auto [label, conf, probs] = OpenAICubes::infer_end_to_end(pixels);
        std::stringstream out;
        out << label << ":" << std::fixed << std::setprecision(2) << (conf * 100.0f);
        for (float p : probs) {
            out << ";" << std::fixed << std::setprecision(4) << p;
        }
        return "INFER_RESP|" + task_id + "|" + out.str();

    } else if (action == "PING") {
        return "PONG|" + task_id + "|OK";
    }

    return "UNKNOWN|" + task_id + "|ERR";
}

int main(int argc, char* argv[]) {
    std::string worker_id = (argc > 1) ? argv[1] : "node_cpp_1";
    std::string host = (argc > 2) ? argv[2] : "127.0.0.1";
    int port = (argc > 3) ? std::stoi(argv[3]) : 9010;
    int threads = (argc > 4) ? std::stoi(argv[4]) : 4;
    g_thread_count = threads;

    std::cout << "[CppWorker " << worker_id << "] Connecting to " << host << ":" << port << " with " << threads << " threads...\n";

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        std::cerr << "Failed to create socket\n";
        return 1;
    }

    struct sockaddr_in serv_addr;
    std::memset(&serv_addr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(port);

    if (inet_pton(AF_INET, host.c_str(), &serv_addr.sin_addr) <= 0) {
        struct hostent* he = gethostbyname(host.c_str());
        if (!he) {
            std::cerr << "Invalid address/Host not found: " << host << "\n";
            close(sock);
            return 1;
        }
        std::memcpy(&serv_addr.sin_addr, he->h_addr_list[0], he->h_length);
    }

    if (connect(sock, (struct sockaddr*)&serv_addr, sizeof(serv_addr)) < 0) {
        std::cerr << "Connection Failed\n";
        close(sock);
        return 1;
    }

    // 1. Send Registration
    std::string reg_msg = "REGISTER|" + worker_id + "|C++|" + std::to_string(threads) + "|CONV,MLP\n";
    send(sock, reg_msg.c_str(), reg_msg.length(), 0);

    // Read ACK
    char buffer[4096];
    std::string accumulated;
    
    // 2. Main processing loop
    while (true) {
        ssize_t bytes_read = recv(sock, buffer, sizeof(buffer) - 1, 0);
        if (bytes_read <= 0) break;

        buffer[bytes_read] = '\0';
        accumulated += buffer;

        size_t newline_pos;
        while ((newline_pos = accumulated.find('\n')) != std::string::npos) {
            std::string line = accumulated.substr(0, newline_pos);
            accumulated.erase(0, newline_pos + 1);

            if (line.rfind("REGISTER_ACK", 0) == 0) {
                std::cout << "[CppWorker " << worker_id << "] Registration confirmed: " << line << "\n";
                continue;
            }

            std::string reply = handle_message(line);
            if (!reply.empty()) {
                reply += "\n";
                send(sock, reply.c_str(), reply.length(), 0);
            }
        }
    }

    std::cout << "[CppWorker " << worker_id << "] Disconnected.\n";
    close(sock);
    return 0;
}

