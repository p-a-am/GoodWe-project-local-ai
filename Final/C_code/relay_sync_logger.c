#include <wiringPi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdbool.h>

#define NUM_RELAYS 6
// Caminho absoluto para o arquivo CSV, para evitar problemas de diretório.
#define CSV_FILE "/home/pam/smartlights/C_code/energy_backend/project_itself/src/projeto_backend_clojure/resources/GoodWe_database.csv"

// Mapeamento de pinos WiringPi para pinos físicos
// pinos físicos 37, 36, 35, 33, 31, 32
int relayPins[NUM_RELAYS] = {25, 27, 24, 23, 22, 26};
int physicalPins[NUM_RELAYS] = {37, 36, 35, 33, 31, 32};
const int specialPin = 7; // Pino físico 7

// Nomes de salas correspondentes
const char* roomNames[NUM_RELAYS] = {
    "Kitchen",
    "Living Room",
    "Bedroom",
    "Office",
    "Restroom",
    "DiningRoom"
};

// Rastrear estados dos relés
int relayStates[NUM_RELAYS] = {0};

// Variáveis de controle de tempo para log a cada 30 minutos
time_t lastLogTime = 0;
const long THIRTY_MINUTES_IN_SECONDS = 30 * 60;

// Variável para rastrear a última modificação do arquivo
time_t lastCSVMod = 0;

// Obter hora arredondada para o próximo 30 minutos (formato HH:MM)
void getRoundedTime(char *buffer, size_t size) {
    time_t rawtime;
    struct tm * timeinfo;
    time(&rawtime);
    timeinfo = localtime(&rawtime);

    int minutes = timeinfo->tm_min;
    if (minutes < 15) timeinfo->tm_min = 0;
    else if (minutes < 45) timeinfo->tm_min = 30;
    else {
        timeinfo->tm_min = 0;
        timeinfo->tm_hour++;
    }
    strftime(buffer, size, "%H:%M", timeinfo);
}

// Obter hora de modificação do arquivo
time_t getFileModTime(const char *path) {
    struct stat attr;
    if (stat(path, &attr) == 0) {
        return attr.st_mtime;
    }
    return 0;
}

// Ler a última linha do arquivo
char* readLastLine(FILE *fp) {
    long pos;
    int c;
    char *line = NULL;
    size_t len = 0;
    ssize_t read;
    if (fseek(fp, 0, SEEK_END) != 0) return NULL;
    pos = ftell(fp);
    if (pos <= 0) return NULL;
    while (pos > 0) {
        pos--;
        if (fseek(fp, pos, SEEK_SET) != 0) return NULL;
        c = fgetc(fp);
        if (c == '\n') break;
    }
    read = getline(&line, &len, fp);
    if (read == -1) {
        free(line);
        return NULL;
    }
    return line;
}

// Sincronizar estados dos relés a partir do arquivo CSV
void syncFromCSV() {
    FILE *file = fopen(CSV_FILE, "r");
    if (file == NULL) {
        perror("Failed to open CSV file for reading");
        return;
    }
    char *lastLinePtr = readLastLine(file);
    if (lastLinePtr == NULL) {
        printf("Could not read last line of CSV.\n");
        fclose(file);
        return;
    }

    char *token;
    char *lineCopy = strdup(lastLinePtr);
    free(lastLinePtr);

    token = strtok(lineCopy, ";");
    bool current = (token && strcmp(token, "\"true\"") == 0);

    token = strtok(NULL, ";");
    char *roomName = token ? token + 1 : NULL;
    if (roomName) roomName[strlen(roomName)-1] = '\0';
    
    printf("Read from CSV: current=%s, roomName=%s\n", current ? "true" : "false", roomName);

    if (current && roomName) {
        for (int i = 0; i < NUM_RELAYS; i++) {
            if (strcmp(roomName, roomNames[i]) == 0) {
                digitalWrite(relayPins[i], HIGH);
                relayStates[i] = 1;
                printf("Syncing: Setting %s (Pin %d) to HIGH.\n", roomName, relayPins[i]);
                break;
            }
        }
    }
    free(lineCopy);
    fclose(file);
}

// Função para logar no CSV com base no estado do pino 7
void logToCSVWithTimer(bool specialPinIsLow) {
    FILE *file = fopen(CSV_FILE, "a");
    if (file == NULL) {
        perror("Failed to open CSV file for logging");
        return;
    }

    char dayBuffer[4];
    char timeBuffer[6];
    time_t rawtime;
    struct tm *timeinfo;

    time(&rawtime);
    timeinfo = localtime(&rawtime);
    strftime(dayBuffer, sizeof(dayBuffer), "%a", timeinfo);
    getRoundedTime(timeBuffer, sizeof(timeBuffer));

    if (specialPinIsLow) {
        fprintf(file, "\n\" \";\" \";\"%s\";\"%s\"", dayBuffer, timeBuffer);
        printf("Logged a blank entry. Pin %d is LOW.\n", specialPin);
    } else {
        for (int i = 0; i < NUM_RELAYS; i++) {
            if (relayStates[i] == 1) {
                fprintf(file, "\n\"%s\";\"%d\";\"%s\";\"%s\"", "true", physicalPins[i], dayBuffer, timeBuffer);
                printf("Logged physical pin %d ON at %s on %s.\n", physicalPins[i], timeBuffer, dayBuffer);
                break;
            }
        }
    }
    fclose(file);
    lastLogTime = time(NULL);
}

int main() {
    printf("Starting relay sync logger...\n");
    printf("Attempting to access CSV at: %s\n", CSV_FILE);

    if (wiringPiSetup() == -1) {
        printf("WiringPi setup failed.\n");
        return 1;
    }

    for (int i = 0; i < NUM_RELAYS; i++) {
        pinMode(relayPins[i], OUTPUT);
    }
    
    pinMode(specialPin, INPUT);

    syncFromCSV();
    lastCSVMod = getFileModTime(CSV_FILE);
    lastLogTime = time(NULL);

    while (1) {
        time_t currentCSVMod = getFileModTime(CSV_FILE);
        if (currentCSVMod != lastCSVMod) {
            printf("CSV modified. Syncing GPIO states...\n");
            lastCSVMod = currentCSVMod;
            syncFromCSV();
        }

        time_t now = time(NULL);
        if (difftime(now, lastLogTime) >= THIRTY_MINUTES_IN_SECONDS) {
            bool specialPinIsLow = (digitalRead(specialPin) == LOW);
            logToCSVWithTimer(specialPinIsLow);
        }

        usleep(2000);
    }
    return 0;
}