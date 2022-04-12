# RRI examples for network byte order

## Introduction
In computing, byte order or endianness is the way simple numeral values are stored. It names in particular the storing order of integers in the main memory. The applicable storage format must be defined if the number of bits required to encode the numeral to be stored is larger than the number of bits available in the smallest addressable unit. Normally, the smallest addressable unit is one byte. If more than one byte is needed for storing, the numeral is then stored in several bytes with the memory addresses stated in immediate sequence. Whilst cross-producer standards have been established with many other types of storage organization, two variants persist for byte order.

## Big Endian
In the case of Big Endian, the byte with the bits of the largest value (i.e. the most significant values) are stored first, i.e. at the lowest memory address. Generally speaking, the term Big Endian defines that data of the largest unit is to be stated first. It can be compared to the German manner of stating the time: hours:minutes:seconds.

## Little Endian
In the case of Little Endian in contrast, the byte with the bits of the lowest value (i.e. the least significant values) are stored at the lowest memory address. Here, the data of the smallest unit is to be stated first. It can be compared to the German manner of stating a date: day:month:year.

## Converting Big Endian to Little Endian
Since it is occasionally necessary to convert the one format to the other (especially in network programming) the commonly used programming languages include special functions for that. "C" for example offers the functions "htonl()" and "ntohl", Perl uses "pack" and "unpack" with the "N" parameter. The network byte order must be given in binary.
