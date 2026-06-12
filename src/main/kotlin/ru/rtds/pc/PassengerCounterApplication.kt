package ru.rtds.pc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PassengerCounterApplication

fun main(args: Array<String>) {
    runApplication<PassengerCounterApplication>(*args)
}
