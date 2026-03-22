CC = gcc
CFLAGS = -Wall -pthread
TARGET = expr_client

all: $(TARGET)

$(TARGET): expr_client.c
	$(CC) $(CFLAGS) -o $(TARGET) expr_client.c

run: $(TARGET)
	./$(TARGET)

clean:
	rm -f $(TARGET)

.PHONY: all run clean
