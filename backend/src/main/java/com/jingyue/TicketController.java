package com.jingyue;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class TicketController {
    final TicketAdmission admission;final OrderService orders;
    public TicketController(TicketAdmission a,OrderService o){admission=a;orders=o;}
    @PostMapping("/ticket-requests") public Map<String,Object> submit(@RequestBody Map<String,Object> b){return admission.submit(b);}
    @GetMapping("/ticket-requests/{rid}") public Map<String,Object> query(@PathVariable String rid){return admission.query(rid);}
    @GetMapping("/ticket-requests/current") public Map<String,Object> currentQuery(@RequestParam long skuId){return admission.current(skuId);}
    @GetMapping("/ticket-skus/{sku}/current-request") public Map<String,Object> current(@PathVariable long sku){return admission.current(sku);}
    @GetMapping("/orders") public Map<String,Object> list(@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return orders.list(state,page,size);}
    @GetMapping("/orders/{id}") public Map<String,Object> detail(@PathVariable String id){return orders.detail(id);}
    @PostMapping("/orders/{id}/mock-payment") public Map<String,Object> pay(@PathVariable String id,@RequestBody Map<String,Object> b){return orders.pay(id,b);}
    @PostMapping("/orders/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id){return orders.cancel(id);}
    @GetMapping("/orders/{id}/ticket") public Map<String,Object> ticket(@PathVariable String id){return orders.ticket(id);}
    @PostMapping("/redemptions") public Map<String,Object> redeem(@RequestBody Map<String,Object> b){return orders.redeem(b);}
}
