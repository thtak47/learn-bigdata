package com.hqbhoho.bigdata.design.pattern.responsibility_chain;

/**
 * describe:
 *
 * @author hqbhoho
 * @version [v1.0]
 * @date 2019/12/05
 */
public class CollegeMaster extends Leader {
    @Override
    void approveVacation(int dayNum) {
        if(dayNum > 10){
            System.out.println(dayNum + " days,too long,CollegeMaster can't approve your vacation.");
            System.out.println("your request will be postd to "+super.getNext());
            super.getNext().approveVacation(dayNum);
        }else{
            System.out.println(dayNum + " days,CollegeMaster approve your vacation.");
        }
    }

    @Override
    public String toString() {
        return "CollegeMaster";
    }
}
