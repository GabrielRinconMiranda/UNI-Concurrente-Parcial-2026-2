#set page(
  paper: "presentation-16-9",
  margin: (x: 2.2cm, top: 2.2cm, bottom: 2.0cm),
  header-ascent: 1.1cm,
  footer-descent: 0.9cm,
  fill: rgb("#0d131f"),
  header: context {
    if counter(page).get().first() > 1 [
      #grid(
        columns: (1fr, auto),
        align: (left, right),
        text(10pt, fill: rgb("#a0aec0"), font: "Liberation Sans")[
          *CC4P1 Concurrent and Distributed Programming* --- Term 2026-II
        ],
        text(10pt, fill: rgb("#63b3ed"), font: "Liberation Sans")[
          *Open AI Cubes*
        ]
      )
      #v(0.15cm)
      #line(length: 100%, stroke: rgb("#2d3748") + 0.8pt)
    ]
  },
  footer: context {
    if counter(page).get().first() > 1 [
      #line(length: 100%, stroke: rgb("#2d3748") + 0.8pt)
      #v(0.15cm)
      #grid(
        columns: (1fr, auto),
        align: (left, right),
        text(9.5pt, fill: rgb("#718096"), font: "Liberation Sans")[
          Facultad de Ciencias --- Universidad Nacional de Ingeniería
        ],
        text(9.5pt, fill: rgb("#a0aec0"), font: "Liberation Sans")[
          Slide #counter(page).display()
        ]
      )
    ]
  }
)

#set text(
  font: "Liberation Sans",
  size: 13pt,
  fill: rgb("#e2e8f0"),
  lang: "en"
)

#set par(
  leading: 0.55em
)

// Slide 1: Title Slide
#align(center + horizon)[
  #image("recursos/logo uni.png", width: 2.2cm)
  #v(0.2cm)
  #text(16pt, weight: "bold", fill: rgb("#63b3ed"))[UNIVERSIDAD NACIONAL DE INGENIERÍA] \
  #text(12pt, fill: rgb("#cbd5e0"))[Facultad de Ciencias --- Escuela Profesional de Ciencia de la Computación] \
  #v(0.4cm)
  #rect(width: 92%, fill: rgb("#1a202c"), stroke: rgb("#3182ce") + 2pt, inset: 16pt, radius: 10pt)[
    #text(26pt, weight: "bold", fill: rgb("#ffffff"))[OPEN AI CUBES] \
    #v(0.2cm)
    #text(14pt, fill: rgb("#63b3ed"))[Modular Distributed Architecture for Neural Network Inference]
  ]
  #v(0.4cm)
  #grid(
    columns: (1fr, 1fr, 1fr),
    gutter: 10pt,
    text(11pt, fill: rgb("#e2e8f0"))[1. *Edwin Arlés Requena Alvarado*],
    text(11pt, fill: rgb("#e2e8f0"))[2. *Gabriel Arturo Rincón Miranda*],
    text(11pt, fill: rgb("#e2e8f0"))[3. *Piero Castillo Solorzano*]
  )
  #v(0.25cm)
  #text(11pt, fill: rgb("#a0aec0"))[CC4P1 Concurrent and Distributed Programming --- October 2026]
]

#pagebreak()

// Slide 2: Problem Definition and Core Requirements
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[1. Architecture Challenge and Technical Requirements]
#v(0.25cm)

#grid(
  columns: (1fr, 1fr),
  gutter: 18pt,
  rect(fill: rgb("#1a202c"), stroke: rgb("#4a5568") + 1pt, inset: 13pt, radius: 8pt)[
    #text(15pt, weight: "bold", fill: rgb("#fc8181"))[Strict Project Constraints] \
    #v(0.2cm)
    - *Standard SDK Exclusivity:* No external computer vision, machine learning, or network libraries (no PyTorch, TensorFlow, OpenCV, or gRPC).
    - *Zero Connection Middleware:* Direct communication using only base language APIs.
    - *Pure Socket & HTTP Standards:* Standard TCP sockets (RFC 793) and standard HTTP REST endpoints (RFC 7230).
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#4a5568") + 1pt, inset: 13pt, radius: 8pt)[
    #text(15pt, weight: "bold", fill: rgb("#68d391"))[Technical Objectives] \
    #v(0.2cm)
    - *Modular Decomposition:* Splitting the CNN workload into independent convolution and classification stages.
    - *Five Programming Languages:* A coordinated cluster with Java, Python, C++ (OpenMP), Rust, and Go.
    - *Dynamic Load Balancing:* Non-blocking round-robin dispatch across active workers.
  ]
)

#pagebreak()

// Slide 3: Modular Neural Pipeline
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[2. Modular Neural Inference Pipeline]
#v(0.25cm)

#align(center)[
  #block(
    width: 100%,
    stroke: rgb("#4a5568") + 1.2pt,
    inset: (x: 10pt, y: 14pt),
    radius: 8pt,
    fill: rgb("#1a202c")
  )[
    #grid(
      columns: (1fr, auto, 1.25fr, auto, 1.15fr, auto, 1.1fr),
      align: horizon + center,
      gutter: 6pt,
      rect(width: 100%, fill: rgb("#2a4365"), stroke: rgb("#63b3ed") + 1.2pt, inset: (x: 4pt, y: 8pt), radius: 6pt)[
        #text(9pt, weight: "bold", fill: rgb("#90cdf4"))[Input Image] \
        #v(2pt)
        #text(11pt, weight: "bold", fill: rgb("#ffffff"))[Raw Pixels] \
        #v(3pt)
        #text(8.5pt, fill: rgb("#cbd5e0"))[$28 times 28$ matrix \ 784 tokens $[0, 1]$]
      ],
      text(14pt, fill: rgb("#cbd5e0"), weight: "bold")[$stretch(arrow.r)$],
      rect(width: 100%, fill: rgb("#744210"), stroke: rgb("#f6ad55") + 1.2pt, inset: (x: 4pt, y: 8pt), radius: 6pt)[
        #text(9pt, weight: "bold", fill: rgb("#fbd38d"))[Convolution Cube] \
        #v(2pt)
        #text(11pt, weight: "bold", fill: rgb("#ffffff"))[Conv + Pool] \
        #v(3pt)
        #text(8.5pt, fill: rgb("#cbd5e0"))[8 filters ($3 times 3$) \ Maps: $8 times 26 times 26$ \ Pool: $13 times 13$]
      ],
      text(14pt, fill: rgb("#cbd5e0"), weight: "bold")[$stretch(arrow.r)$],
      rect(width: 100%, fill: rgb("#234e52"), stroke: rgb("#4fd1c5") + 1.2pt, inset: (x: 4pt, y: 8pt), radius: 6pt)[
        #text(9pt, weight: "bold", fill: rgb("#81e6d9"))[Feature Vector] \
        #v(2pt)
        #text(11pt, weight: "bold", fill: rgb("#ffffff"))[Dense Tensor] \
        #v(3pt)
        #text(8.5pt, fill: rgb("#cbd5e0"))[1352 dimensions \ L2 normalized \ Wire payload]
      ],
      text(14pt, fill: rgb("#cbd5e0"), weight: "bold")[$stretch(arrow.r)$],
      rect(width: 100%, fill: rgb("#44337a"), stroke: rgb("#b794f4") + 1.2pt, inset: (x: 4pt, y: 8pt), radius: 6pt)[
        #text(9pt, weight: "bold", fill: rgb("#d6bcfa"))[Classifier Cube] \
        #v(2pt)
        #text(11pt, weight: "bold", fill: rgb("#ffffff"))[MLP Softmax] \
        #v(3pt)
        #text(8.5pt, fill: rgb("#cbd5e0"))[Cosine similarity \ Softmax ($tau = 0.08$) \ 5 class output]
      ]
    )
  ]
]

#v(0.3cm)
#text(11pt, style: "italic", fill: rgb("#a0aec0"))[
  Modular stages exchange data across TCP sockets or HTTP endpoints without shared memory dependencies.
]

#pagebreak()

// Slide 4: Heterogeneous Cluster Across Five Languages
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[3. Heterogeneous Cluster Across Five Languages]
#v(0.25cm)

#table(
  columns: (2.6cm, 3.0cm, 4.2cm, 9.2cm),
  stroke: rgb("#4a5568"),
  fill: (col, row) => if row == 0 { rgb("#2d3748") } else { rgb("#1a202c") },
  [*Component*], [*Language*], [*Concurrency Mechanism*], [*Role in the Architecture*],
  [Orchestrator], [Java 25 (LP1)], [ThreadPoolExecutor + Sockets], [Central server, dynamic registry, and desktop GUI.],
  [Worker LP2], [Python 3], [Multiprocessing + Stream Buffers], [Modular worker with native socket and HTTP handlers.],
  [Worker LP3], [C++17 (OpenMP)], [Multi-core shared memory O2], [High-throughput native spatial convolution.],
  [Worker LP4], [Rust 2021], [Native threads (Zero-Cost)], [Thread-safe concurrency with compile-time memory guarantees.],
  [Worker LP5], [Go 1.22], [Goroutines + net.Conn], [Lightweight concurrent routines and efficient memory handling.]
)

#pagebreak()

// Slide 5: Communication Protocols
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[4. Dual-Protocol Design: Direct TCP and Cloud HTTP]
#v(0.25cm)

#grid(
  columns: (1fr, 1fr),
  gutter: 18pt,
  rect(fill: rgb("#1a202c"), stroke: rgb("#4a5568") + 1pt, inset: 13pt, radius: 8pt)[
    #text(15pt, weight: "bold", fill: rgb("#63b3ed"))[Protocol Format (RFC 793)] \
    #v(0.2cm)
    `ACTION|IDENTIFIER|PAYLOAD\n`
    #v(0.2cm)
    - *Delimiter:* Newline (`\n`) delimits complete frames.
    - *Fields:* Separated by pipes (`|`).
    - *Action:* `REGISTER`, `CONV_REQ`, `MLP_REQ`, `CLIENT_REQ`.
    - *Payload:* Plaintext CSV of floating-point values.
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#4a5568") + 1pt, inset: 13pt, radius: 8pt)[
    #text(15pt, weight: "bold", fill: rgb("#68d391"))[Distributed Handoff Lifecycle] \
    #v(0.2cm)
    1. *Client:* Sends image request to port `8085` or `POST /infer`.
    2. *Server:* Selects least-loaded convolution worker.
    3. *Conv Stage:* Worker applies 8 filters and returns feature vector.
    4. *MLP Stage:* Second worker evaluates Softmax probabilities.
    5. *Response:* Orchestrator sends predicted label and latency.
  ]
)

#pagebreak()

// Slide 6: The Four Execution Modes
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[5. Evaluation Across Four Execution Modes]
#v(0.25cm)

#grid(
  columns: (1fr, 1fr),
  gutter: 14pt,
  rect(fill: rgb("#1a202c"), stroke: rgb("#3182ce") + 1pt, inset: 11pt, radius: 8pt)[
    #text(14pt, weight: "bold", fill: rgb("#63b3ed"))[1. Sequential (1 Process, 1 Thread)] \
    - Single-threaded local execution.
    - Protected by `sequentialLock`.
    - Baseline latency: ~50 ms per image.
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#48bb78") + 1pt, inset: 11pt, radius: 8pt)[
    #text(14pt, weight: "bold", fill: rgb("#68d391"))[2. Parallel (N Cores, H Threads)] \
    - Local multi-core shared memory parallelism.
    - Uses Java `ThreadPoolExecutor`.
    - Latency drops to ~18 ms (2.8x speedup).
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#ed8936") + 1pt, inset: 11pt, radius: 8pt)[
    #text(14pt, weight: "bold", fill: rgb("#f6ad55"))[3. Distributed (M Machines, Serial Pipeline)] \
    - Workers run internal threads in parallel.
    - Sequential gating across pipeline stages.
    - Enforced by `distributedLock`.
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#e53e3e") + 1pt, inset: 11pt, radius: 8pt)[
    #text(14pt, weight: "bold", fill: rgb("#fc8181"))[4. Parallel Distributed (M Machines, Concurrent)] \
    - Simultaneous dispatch across all worker nodes.
    - Handles concurrent clients with round-robin balancing.
    - Maximum cluster throughput under load.
  ]
)

#pagebreak()

// Slide 7: Throughput Scaling Results
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[6. Cluster Throughput: Multi-Node Scaling Analysis]
#v(0.2cm)

#grid(
  columns: (1.3fr, 1fr),
  gutter: 14pt,
  image("figures/fig1_throughput_vs_cubes.png", width: 100%),
  [
    #text(16pt, weight: "bold", fill: rgb("#63b3ed"))[Key Findings] \
    #v(0.2cm)
    - *Under 20 Concurrent Clients:* Throughput scales from *7.85 img/s* (1 node) to *13.45 img/s* (4 nodes).
    - *Measured Speedup:* 1.71x increase in aggregate throughput under heavy client demand.
    - *Mean Latency Reduction:* Drops from 1703 ms to 1143 ms (-33%).
    - *P95 Tail Latency Reduction:* Falls from 2547 ms to 1479 ms (-42%).
  ]
)

#pagebreak()

// Slide 8: Amdahl's Law and the Straggler Effect
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[7. Amdahl's Law Validation and the Straggler Effect]
#v(0.2cm)

#grid(
  columns: (1.3fr, 1fr),
  gutter: 14pt,
  image("figures/fig3_speedup_vs_amdahl.png", width: 100%),
  [
    #text(16pt, weight: "bold", fill: rgb("#f6ad55"))[Theoretical Analysis] \
    #v(0.2cm)
    - *Parallel Fraction ($p$):* Measured speedup closely follows Amdahl's Law with $p = 0.85$ to $0.90$.
    - *The Straggler Effect in K=5:* In isolation, Python takes 820 ms compared to ~180 ms for C++, Rust, and Go.
    - *Throughput Impact:* Adding Python to symmetric round-robin balancing reduces cluster throughput to 5.23 img/s, confirming theoretical predictions.
  ]
)

#pagebreak()

// Slide 9: Stochastic Saturation Stress Testing
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[8. Saturated Stress Testing with Random Delays]
#v(0.25cm)

#rect(fill: rgb("#1a202c"), stroke: rgb("#3182ce") + 1.5pt, inset: 13pt, radius: 8pt, width: 100%)[
  #text(16pt, weight: "bold", fill: rgb("#68d391"))[Test Parameters (`scripts/stress_test_saturation.py`)] \
  #v(0.15cm)
  - *Total Transactions:* 250 continuous inference requests.
  - *Client Concurrency:* 20 simultaneous threads.
  - *Random Delay:* Uniformly distributed between 5 ms and 45 ms between requests.
]

#v(0.3cm)
#grid(
  columns: (1fr, 1fr, 1fr, 1fr),
  gutter: 10pt,
  rect(fill: rgb("#1a202c"), stroke: rgb("#48bb78") + 1pt, inset: 10pt, radius: 6pt)[
    #text(11pt, fill: rgb("#a0aec0"))[Success Rate] \
    #text(18pt, weight: "bold", fill: rgb("#48bb78"))[100.0%] \
    #text(9pt, fill: rgb("#718096"))[250/250 valid responses]
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#63b3ed") + 1pt, inset: 10pt, radius: 6pt)[
    #text(11pt, fill: rgb("#a0aec0"))[Throughput] \
    #text(18pt, weight: "bold", fill: rgb("#63b3ed"))[13.71 req/s] \
    #text(9pt, fill: rgb("#718096"))[Duration: 18.23 s]
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#f6ad55") + 1pt, inset: 10pt, radius: 6pt)[
    #text(11pt, fill: rgb("#a0aec0"))[Mean Latency] \
    #text(18pt, weight: "bold", fill: rgb("#f6ad55"))[612.5 ms] \
    #text(9pt, fill: rgb("#718096"))[P50: 580.3 ms]
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#fc8181") + 1pt, inset: 10pt, radius: 6pt)[
    #text(11pt, fill: rgb("#a0aec0"))[P95 Tail Latency] \
    #text(18pt, weight: "bold", fill: rgb("#fc8181"))[985.4 ms] \
    #text(9pt, fill: rgb("#718096"))[Max: 1290.4 ms]
  ]
)

#pagebreak()

// Slide 10: Client Inference Workspace Operational Evidence
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[9. Client Inference Workspace Operational Evidence]
#v(0.2cm)

#grid(
  columns: (1.4fr, 1fr),
  gutter: 14pt,
  image("recursos/user frontend web evidence.png", width: 100%),
  [
    #text(15pt, weight: "bold", fill: rgb("#63b3ed"))[Workspace Features] \
    #v(0.2cm)
    - *Session Concurrency:* Dynamic client session identifier (`Client-XXXX`) allocated per thread.
    - *Photographic Ingestion:* Aspect-preserving proportional preview with real animal test samples.
    - *Inference Telemetry:* Sub-millisecond latency tracking, confidence breakdown, and trace logging.
    - *Cluster Controls:* Dynamic cluster execution mode selection on the fly.
  ]
)

#pagebreak()

// Slide 11: Cluster Administration Console Operational Evidence
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[10. Cluster Administration Console Operational Evidence]
#v(0.2cm)

#grid(
  columns: (1.4fr, 1fr),
  gutter: 14pt,
  image("recursos/admin frontend web evidence.png", width: 100%),
  [
    #text(15pt, weight: "bold", fill: rgb("#68d391"))[Console Features] \
    #v(0.2cm)
    - *Node Fleet Overview:* Real-time tracking of active vs standby cubes across all 5 languages.
    - *Completed Task Counters:* Monotonically increasing task execution counters per worker.
    - *Cluster KPI Cards:* Total registered nodes, active cores, and throughput telemetry.
    - *Audit Log Stream:* Formatted event stream documenting connections, dispatches, and completions.
  ]
)

#pagebreak()

// Slide 12: Multi-Host Cloud Deployment and Rubric Fulfillment
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[11. Multi-Host Cloud Deployment and Rubric Fulfillment]
#v(0.25cm)

#grid(
  columns: (1fr, 1fr),
  gutter: 16pt,
  rect(fill: rgb("#1a202c"), stroke: rgb("#4a5568") + 1pt, inset: 13pt, radius: 8pt)[
    #text(15pt, weight: "bold", fill: rgb("#63b3ed"))[Distributed Multi-Host Deployment] \
    #v(0.2cm)
    - *Physical Isolation:* Central orchestrator and workers run on separate hosts with independent network addresses.
    - *Dual Transport:* High-speed raw TCP for low-latency clusters and standard HTTP/REST for public cloud hosting.
    - *Automated Orchestration:* Single-command cross-platform launch scripts (`launch_cluster.sh`, `launch_cluster.bat`).
  ],
  rect(fill: rgb("#1a202c"), stroke: rgb("#4a5568") + 1pt, inset: 13pt, radius: 8pt)[
    #text(15pt, weight: "bold", fill: rgb("#68d391"))[Academic Rubric Compliance] \
    #v(0.2cm)
    - *Zero External ML Frameworks:* Pure standard library implementation across all 5 programming languages.
    - *Thread Safety & Load Balancing:* Concurrent client request isolation with dynamic round-robin scheduling.
    - *Empirical Verification:* Thorough Amdahl's Law validation and multi-client saturation stress testing.
  ]
)

#pagebreak()

// Slide 13: Conclusions
#text(20pt, weight: "bold", fill: rgb("#ffffff"))[12. Summary of Results and Academic Deliverables]
#v(0.35cm)

#rect(fill: rgb("#1a202c"), stroke: rgb("#3182ce") + 1.5pt, inset: 14pt, radius: 8pt, width: 100%)[
  #grid(
    columns: (1fr, 1fr),
    gutter: 14pt,
    [
      #text(14pt, weight: "bold", fill: rgb("#68d391"))[Technical Achievements] \
      - Five languages integrated into a working cluster.
      - Standard SDK networking with zero external frameworks.
      - Over 95% classification accuracy on real photographs.
      - 1.71x speedup and 42% P95 latency reduction with 4 nodes.
    ],
    [
      #text(14pt, weight: "bold", fill: rgb("#63b3ed"))[Deliverables Checklist] \
      - `docs/TECHNICAL_REPORT_OPEN_AI_CUBES.pdf` (Technical Report).
      - `docs/PRESENTATION_OPEN_AI_CUBES.pdf` (Presentation Slides).
      - Complete cross-platform execution scripts and test suite.
      - Dual desktop Swing GUI and responsive web consoles.
    ]
  )
]

#v(0.6cm)
#align(center)[
  #text(18pt, weight: "bold", fill: rgb("#ffffff"))[Thank You for Your Attention] \
  #v(0.15cm)
  #text(13pt, fill: rgb("#63b3ed"))[Questions and Live Demonstration]
]
