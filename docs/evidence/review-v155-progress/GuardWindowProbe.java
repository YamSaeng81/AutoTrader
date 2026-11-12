package com.cryptoautotrader.api.service;
import com.cryptoautotrader.strategy.Candle;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
public class GuardWindowProbe {
  static List<Candle> candles(Instant open){
    var r=new ArrayList<Candle>();
    for(int i=299;i>=0;i--)r.add(Candle.builder().time(open.minus(i,ChronoUnit.HOURS)).build());
    return r;
  }
  static void check(String tag,Instant open,boolean fresh){
    Instant e=open.plus(70,ChronoUnit.MINUTES);
    String p=CandleFreshnessGuard.problem(candles(open),"H1",e.minusMillis(1));
    System.out.println(tag+" last="+open+" at="+e.minusMillis(1)+" result="+(p==null?"FRESH":p));
    if((p==null)!=fresh)throw new AssertionError(tag);
  }
  public static void main(String[]a){
    var w1=Instant.parse("2026-11-12T03:11:00Z");
    var w2=Instant.parse("2026-11-12T03:58:00Z");
    Instant c1=w1.minus(61,ChronoUnit.MINUTES).minusSeconds(22).plusMillis(371);
    Instant c2=w2.minus(61,ChronoUnit.MINUTES).minusSeconds(22).plusMillis(371);
    check("old-N15-early-minute",c1,true);
    check("old-N15-late-minute",c2,false);
    check("hour-aligned-early",c1.truncatedTo(ChronoUnit.HOURS),true);
    check("hour-aligned-late",c2.truncatedTo(ChronoUnit.HOURS),true);
    Instant key1=c2.truncatedTo(ChronoUnit.HOURS), key2=c2.minusSeconds(5).truncatedTo(ChronoUnit.HOURS);
    if(!key1.equals(key2))throw new AssertionError("collision witness");
    System.out.println("two old offset rounds truncate to SAME key="+key1+"; isolation required");
    System.out.println("5/5 guard/key witnesses; not N15 full flow or PostgreSQL verification");
  }
}
