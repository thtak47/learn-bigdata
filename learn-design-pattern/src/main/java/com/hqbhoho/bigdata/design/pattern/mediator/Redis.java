package com.hqbhoho.bigdata.design.pattern.mediator;

/**
 * describe:
 *
 * @author hqbhoho
 * @version [v1.0]
 * @date 2019/12/07
 */
public class Redis extends AbstractDataBase {
    @Override
    public void syncData(String data) {
        this.getDataBaseMediator().syncData("REDIS",data);
    }
}
