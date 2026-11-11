import com.cryptoautotrader.api.service.*;
import com.cryptoautotrader.api.entity.*;
import com.cryptoautotrader.api.repository.*;
import java.math.*;
import java.util.*;
import static org.mockito.Mockito.*;

public class RiskReview949 {
  public static void main(String[]args) {
    boolean base=args.length>0 && args[0].equals("base");
    var config=RiskConfigEntity.codeDefaults();
    var cr=mock(RiskConfigRepository.class); var pr=mock(PositionRepository.class);
    var svc=new RiskManagementService(cr,pr,mock(TradeLogRepository.class),mock(LiveTradingSessionRepository.class));
    when(cr.findTopByOrderByIdDesc()).thenReturn(Optional.of(config));
    var s=LiveTradingSessionEntity.builder().id(1L).initialCapital(new BigDecimal("20200")).mddPeakCapital(new BigDecimal("20200")).totalAssetKrw(new BigDecimal("20200")).build();
    if (!base) {
      config.setCircuitBreakerEnabled(true);config.setConsecutiveLossLimit(5);
      // Identical cashflow: invested 20000, buy fee 10, sell net 19995.
      // LIVE risk input excludes buy fee: +5. PAPER input includes it: -5.
      var l=svc.checkCircuitBreakerFromValues(new BigDecimal("20200"),new BigDecimal("20200"),new BigDecimal("20200"),Collections.nCopies(5,new BigDecimal("5")));
      var p=svc.checkCircuitBreakerFromValues(new BigDecimal("20200"),new BigDecimal("20200"),new BigDecimal("20200"),Collections.nCopies(5,new BigDecimal("-5")));
      System.out.println("[R5] same cashflow / 5 closes: LIVE-input triggered="+l.isTriggered()+" PAPER-input triggered="+p.isTriggered());
      if(l.isTriggered() || !p.isTriggered()) throw new AssertionError("witness mismatch");
    }
    when(pr.findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc("LIVE",1L,"CLOSED")).thenThrow(new IllegalStateException("review repository failure"));
    for(boolean enabled:List.of(false,true)) {
      config.setCircuitBreakerEnabled(enabled);s.setTotalAssetKrw(enabled?BigDecimal.ZERO:new BigDecimal("20200"));
      boolean threw=false;Boolean triggered=null;
      try { triggered=svc.checkCircuitBreaker(s).isTriggered(); } catch(IllegalStateException e) { threw=true; }
      System.out.println("[R6] "+(base?"af7ffa58":"9491166f")+" enabled="+enabled+" MDD="+enabled+" repositoryException="+threw+" triggered="+triggered);
      if(base ? (threw || triggered!=enabled) : !threw) throw new AssertionError("witness mismatch");
    }
  }
}
