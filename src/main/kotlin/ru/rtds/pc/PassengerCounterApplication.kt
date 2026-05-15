package ru.rtds.pc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PassengerCounterApplication

fun main(args: Array<String>) {
    runApplication<PassengerCounterApplication>(*args)
}
