package com.wechatjava;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.wechatjava"})
@MapperScan(basePackages = {"com.wechatjava.mappers"})
@EnableTransactionManagement
@EnableScheduling
public class WeChatJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeChatJavaApplication.class, args);

    }

}
