package example

import org.apache.commons.lang3.StringUtils

fun main(args: Array<String>) {
    println(StringUtils.capitalize(System.getProperty("example.message", "hello")))
    args.forEach(::println)
}
