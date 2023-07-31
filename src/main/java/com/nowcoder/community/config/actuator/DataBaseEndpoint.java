package com.nowcoder.community.config.actuator;

import com.nowcoder.community.util.CommunityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * @Author wq
 * @Date 2023/07/14 21:36
 **/
@Component
@Endpoint(id = "database")
@Slf4j
public class DataBaseEndpoint {
    @Resource
    private DataSource dataSource;
    @ReadOperation
    public String checkConnection(){
        try( Connection connection = dataSource.getConnection()) {
           return CommunityUtil.getJSONString(0,"获取连接成功！");
        } catch (SQLException e) {
            log.error("获取连接失败："+e.getMessage());
            return CommunityUtil.getJSONString(1,"获取连接失败！");
        }
    }
}
