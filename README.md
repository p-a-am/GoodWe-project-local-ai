# GoodWe Project Local AI

This project provides an **AI system that can run locally on a Raspberry Pi** to support the **GoodWe renewable energy project**.  
The main objective is to enable **edge AI processing** without requiring constant internet access, improving autonomy, reliability, and security.

---

## Features 
- Runs locally on **Raspberry Pi** devices.
- Lightweight AI processing for **real-time decision-making**.
- Integration with **GoodWe inverters, batteries, and energy management systems**.
- Written in **C++**, optimized for performance on embedded hardware.
- Includes **Clojure scripts** for configuration, automation, and testing.

---

## Requirements 

Before running the project, ensure the following dependencies are installed:

- [`wiringPi`](http://wiringpi.com/) – GPIO library for Raspberry Pi.
- `unistd.h` – POSIX standard header (included in most Linux distributions).
- [`Clojure`](https://clojure.org/guides/getting_started) – functional language used for automation and higher-level orchestration.

---

## Installation 

1. **Update your Raspberry Pi**
   ```bash
   sudo apt update && sudo apt upgrade -y
