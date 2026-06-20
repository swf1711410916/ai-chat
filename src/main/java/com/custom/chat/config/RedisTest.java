package com.custom.chat.config;

import redis.clients.jedis.Jedis;

public class RedisTest {
    public static void main(String[] args) {
        // 这里的 IP 和 密码 换成你当前真实的配置
        try (Jedis jedis = new Jedis("192.168.134.21", 6379)) {
            String authResponse = jedis.auth("Redis@123!");
            System.out.println("密码验证结果: " + authResponse); // 正常应该输出 OK

            System.out.println("测试 FT._LIST 命令: " + jedis.sendCommand(() -> "FT._LIST".getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
