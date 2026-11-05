package com.precheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 审批材料预检助手 —— 启动入口。
 *
 * <p>这个产品只做一件事：在申请进入审批流之前，把材料检查一遍，
 * 告诉员工"缺什么、差多少、怎么改"。批准或拒绝永远由人来做。
 */
@SpringBootApplication
public class ExpensePrecheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpensePrecheckApplication.class, args);
    }
}
