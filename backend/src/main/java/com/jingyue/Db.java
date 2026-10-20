package com.jingyue;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

@Component
public class Db {
    public final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public Db(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }
    public List<Map<String,Object>> rows(String sql,Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql,Object... args) {
        List<Map<String,Object>> rows=rows(sql,args);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Object> need(String sql,Object... args) {
        Map<String,Object> row=one(sql,args);
        if(row==null) throw new ApiFault(404,"NOT_FOUND","内容不存在或无权访问");
        return row;
    }
    public long count(String sql,Object... args) { Long result=jdbc.queryForObject(sql,args,Long.class); return result==null?0:result; }
    public int update(String sql,Object... args) { return jdbc.update(sql,args); }
    public <T> T tx(Supplier<T> work) {
        for(int attempt=0;;attempt++) {
            try { return transaction.execute(status->work.get()); }
            catch(CannotAcquireLockException|DeadlockLoserDataAccessException e) {
                if(attempt>=2) throw e;
                // A fresh transaction retries the entire SQL unit. No remote calls in work.
            }
        }
    }
    public void audit(long actor,String action,String object,String before,String after) {
        update("INSERT INTO tb_operation_audit(actor_id,action,object_id,before_state,after_state,created_at) VALUES(?,?,?,?,?,?)",
            actor,action,object,before,after,System.currentTimeMillis());
    }
}
