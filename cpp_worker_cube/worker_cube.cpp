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

void run_http_worker(int port, int threads) {
    g_thread_count = threads;
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);
    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "[Worker-CPP-HTTP] Bind failed on port " << port << std::endl;
        return;
    }
    listen(server_fd, 10);
    std::cout << "[Worker-CPP-HTTP] Listening on port " << port << " (OpenMP threads: " << threads << ")...\n";

    while (true) {
        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) continue;

        std::string request;
        char buf[4096];
        int n;
        while ((n = read(client_fd, buf, sizeof(buf) - 1)) > 0) {
            buf[n] = '\0';
            request.append(buf, n);
            if (request.find("\r\n\r\n") != std::string::npos) {
                size_t cl_pos = request.find("Content-Length: ");
                if (cl_pos == std::string::npos) cl_pos = request.find("content-length: ");
                if (cl_pos != std::string::npos) {
                    size_t cl_end = request.find("\r\n", cl_pos);
                    int content_len = std::stoi(request.substr(cl_pos + 16, cl_end - (cl_pos + 16)));
                    size_t body_start = request.find("\r\n\r\n") + 4;
                    while (request.length() - body_start < (size_t)content_len) {
                        int more = read(client_fd, buf, sizeof(buf) - 1);
                        if (more <= 0) break;
                        buf[more] = '\0';
                        request.append(buf, more);
                    }
                }
                break;
            }
        }

        std::string resp_body;
        std::string content_type = "text/plain";
        size_t path_start = request.find(' ');
        size_t path_end = request.find(' ', path_start + 1);
        std::string path = (path_start != std::string::npos && path_end != std::string::npos)
                         ? request.substr(path_start + 1, path_end - path_start - 1) : "/";
        size_t body_start = request.find("\r\n\r\n");
        std::string body = (body_start != std::string::npos) ? request.substr(body_start + 4) : "";

        if (path == "/health") {
            content_type = "application/json";
            resp_body = "{\"status\":\"ok\",\"worker\":\"node_cpp_cloud\",\"language\":\"C++\",\"threads\":" + std::to_string(threads) + "}\n";
        } else if (path == "/conv") {
            std::string msg = "CONV_REQ|http|" + body;
            std::string r = handle_message(msg);
            size_t p = r.rfind('|');
            resp_body = (p != std::string::npos) ? r.substr(p + 1) : "";
        } else if (path == "/mlp") {
            std::string msg = "MLP_REQ|http|" + body;
            std::string r = handle_message(msg);
            size_t p = r.rfind('|');
            resp_body = (p != std::string::npos) ? r.substr(p + 1) : "";
        } else if (path == "/infer") {
            std::string msg = "INFER_REQ|http|" + body;
            std::string r = handle_message(msg);
            size_t p = r.rfind('|');
            resp_body = (p != std::string::npos) ? r.substr(p + 1) : "";
        }

        std::string response = "HTTP/1.1 200 OK\r\nContent-Type: " + content_type +
                               "\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " +
                               std::to_string(resp_body.size()) + "\r\nConnection: close\r\n\r\n" + resp_body;
        write(client_fd, response.c_str(), response.size());
        close(client_fd);
    }
}

int main(int argc, char* argv[]) {
    if (argc > 1 && std::string(argv[1]) == "--http") {
        int h_port = 8083;
        char* env_p = std::getenv("PORT");
        if (env_p) h_port = std::stoi(env_p);
        if (argc > 2) {
            std::string p_arg = argv[2];
            if (p_arg[0] == ':') p_arg = p_arg.substr(1);
            if (!p_arg.empty() && std::isdigit(p_arg[0])) h_port = std::stoi(p_arg);
        }
        int h_threads = (argc > 3) ? std::stoi(argv[3]) : 4;
        run_http_worker(h_port, h_threads);
        return 0;
    }

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

