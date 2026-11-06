package com.karic.penny;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Deterministic reconciliation and analytics; independent of Android and import order. */
public final class Ledger {
    static final BigDecimal ZERO = BigDecimal.ZERO;
    public static final class Edit {
        public String category, transferPeer;
        public boolean internal;
        public Edit(String category, boolean internal, String peer) { this.category=category; this.internal=internal; this.transferPeer=peer; }
    }
    public static final class Entry {
        public String key, title, category, direction, peer;
        public Transaction t;
        public BigDecimal amount, fee;
        public long occurredAt;
        public Transaction.Status status;
        public boolean internal, conflict, feeRefunded;
        public final List<Transaction> sources = new ArrayList<>();
        public final Set<String> warnings = new LinkedHashSet<>();
        public final List<Balance> reports = new ArrayList<>();
        public String accountKey() { return t.accountKey(); }
        public boolean credit() { return "IN".equals(direction); }
        public boolean counted() { return !conflict && status==Transaction.Status.COMPLETED && amount!=null && direction!=null; }
    }
    public static final class Balance {
        public BigDecimal value;
        public long at;
        public String type, timeSource, sourceKey;
        Balance(Transaction t) { value=t.reportedBalance; at=t.timestamp; type=t.balanceType; timeSource=t.timeSource; sourceKey=t.sourceKey; }
    }
    public static final class Account {
        public String key, label, kind;
        public boolean identifierKnown;
        public Balance latest;
        public final Map<String,Balance> byType = new TreeMap<>();
        public final Set<String> warnings = new LinkedHashSet<>();
    }
    public static final class Totals {
        public BigDecimal income=ZERO, principal=ZERO, fees=ZERO, spending=ZERO, net=ZERO;
        public int pending, excluded, unknownFees, count;
        public final Map<String,BigDecimal> categories=new TreeMap<>(), accounts=new TreeMap<>();
    }
    public final List<Entry> entries = new ArrayList<>();
    public final Map<String,Account> accounts = new TreeMap<>();
    public final Set<String> warnings = new LinkedHashSet<>();

    public Ledger(Collection<Transaction> observations, Map<String,Edit> edits) {
        List<Transaction> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparingLong((Transaction t)->t.timestamp).thenComparing(t->t.sourceKey));
        Map<String,Transaction> identities = new HashMap<>();
        for(Transaction t:sorted) if(t.transactionId!=null) identities.put(id(t,t.transactionId),t);
        Map<String,List<Transaction>> groups = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for(Transaction t:sorted) {
            if(!seen.add(t.sourceKey))continue;
            String key = key(t);
            if(t.originalId!=null && identities.containsKey(id(t,t.originalId))
                    && (t.status==Transaction.Status.REVERSED || t.status==Transaction.Status.COMPLETED)) key=id(t,t.originalId);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(t);
        }
        for(Map.Entry<String,List<Transaction>> group:groups.entrySet()) entries.add(reconcile(group.getKey(),group.getValue()));
        for(Entry e:entries) {
            Edit edit=edits.get(e.key);
            if(edit!=null) { if(edit.category!=null&&!edit.category.isEmpty())e.category=edit.category; e.internal=edit.internal; e.peer=edit.transferPeer; }
        }
        // Only a shared, explicit reference plus matching opposite legs establishes an automatic transfer.
        for(Entry out:entries) if(out.counted()&&!out.credit()&&!out.internal&&out.t.transferHint&&out.t.transactionId!=null) {
            List<Entry> matches=new ArrayList<>();
            for(Entry in:entries) if(in.counted()&&in.credit()&&!in.internal&&!in.accountKey().equals(out.accountKey())
                    && out.amount.compareTo(in.amount)==0 && out.t.transactionId.equalsIgnoreCase(in.t.transactionId==null?"":in.t.transactionId)
                    && Math.abs(out.occurredAt-in.occurredAt)<=1800000L && !edits.containsKey(out.key)&&!edits.containsKey(in.key)) matches.add(in);
            if(matches.size()==1) { Entry in=matches.get(0); out.internal=in.internal=true; out.peer=in.key; in.peer=out.key; }
        }
        for(Entry e:entries) if(e.internal) {
            Entry peer=find(e.peer);
            if(peer==null||!validPair(e,peer)) e.warnings.add("Internal transfer confirmed by you; matching other leg unavailable");
        }
        buildAccounts();
        flagPotentialDuplicates();
        entries.sort(Comparator.comparingLong((Entry e)->e.occurredAt).reversed().thenComparing(e->e.key));
    }
    static String id(Transaction t,String ref) { return t.accountKey()+":id:"+ref.toUpperCase(Locale.ROOT); }
    public static String key(Transaction t) { return t.transactionId==null?t.accountKey()+":sms:"+t.sourceKey:id(t,t.transactionId); }
    public Entry find(String key) { if(key==null)return null; for(Entry e:entries)if(e.key.equals(key))return e; return null; }
    public static boolean validPair(Entry a,Entry b) {
        return a.counted()&&b.counted()&&!a.accountKey().equals(b.accountKey())&&a.credit()!=b.credit()&&a.amount.compareTo(b.amount)==0;
    }

    private Entry reconcile(String key,List<Transaction> sources) {
        Entry e=new Entry(); e.key=key; e.sources.addAll(sources);
        e.t=sources.get(0); e.occurredAt=e.t.timestamp; e.title=e.t.description; e.category=e.t.category;
        e.status=Transaction.Status.UNKNOWN;
        boolean completed=false,failed=false; long firstCompleted=Long.MAX_VALUE;
        Map<String,List<Transaction>> balanceGroups=new TreeMap<>();
        for(Transaction t:sources) {
            e.warnings.addAll(t.warnings); e.feeRefunded|=t.feeRefunded;
            if(t.status==Transaction.Status.COMPLETED) { completed=true; firstCompleted=Math.min(firstCompleted,t.timestamp); }
            failed|=t.status==Transaction.Status.FAILED;
            if(rank(t.status)>rank(e.status))e.status=t.status;
            if(t.amount!=null) {
                if(e.amount!=null&&e.amount.compareTo(t.amount)!=0) { e.conflict=true; e.warnings.add("Conflicting amounts for one transaction ID; excluded from totals"); }
                else e.amount=t.amount;
            }
            if(t.direction!=null&&t.status!=Transaction.Status.REVERSED) {
                if(e.direction!=null&&!e.direction.equals(t.direction)) {e.conflict=true;e.warnings.add("Conflicting directions; excluded from totals");}
                else e.direction=t.direction;
            }
            if(t.fee!=null) {
                if(e.fee!=null&&e.fee.compareTo(t.fee)!=0) {e.conflict=true;e.warnings.add("Conflicting fees; excluded from totals");}
                else e.fee=t.fee;
            }
            if(t.description!=null && (e.title==null||t.description.length()>e.title.length()))e.title=t.description;
            if("Airtime & Data".equals(t.category))e.category=t.category;
            if(t.reportedBalance!=null&&balanceStatus(t.status))balanceGroups.computeIfAbsent(t.status+":"+t.balanceType,k->new ArrayList<>()).add(t);
        }
        if(firstCompleted!=Long.MAX_VALUE)e.occurredAt=firstCompleted;
        if(completed&&failed) {e.conflict=true;e.warnings.add("Both failed and completed confirmations; excluded until verified");}
        if(e.status==Transaction.Status.REVERSED&&sources.size()==1&&e.t.originalId!=null)e.warnings.add("Reversal original not found; history incomplete");
        if(e.title==null||e.title.trim().isEmpty())e.title=e.t.provider+" SMS record";
        if(e.sources.size()>1)e.warnings.add(e.sources.size()+" confirmations reconciled into one record");
        for(List<Transaction> reports:balanceGroups.values()) {
            Transaction selected=reports.get(0); boolean conflict=false;
            for(int i=1;i<reports.size();i++) {
                Transaction n=reports.get(i);
                if(compatibleBalance(selected.reportedBalance,n.reportedBalance)) {
                    if(n.reportedBalance.scale()>selected.reportedBalance.scale() || (n.reportedBalance.scale()==selected.reportedBalance.scale()&&n.timestamp>selected.timestamp))selected=n;
                } else { conflict=true; }
            }
            if(conflict) {e.warnings.add("Conflicting reported balances for this event; balance unavailable");}
            else if(!e.conflict)e.reports.add(new Balance(selected));
        }
        return e;
    }
    private static int rank(Transaction.Status s) {
        switch(s) {case REVERSED:return 6;case COMPLETED:return 5;case PENDING:return 4;case FAILED:return 3;case APPROVAL:return 2;default:return 1;}
    }
    private static boolean balanceStatus(Transaction.Status s) { return s==Transaction.Status.COMPLETED||s==Transaction.Status.PENDING||s==Transaction.Status.REVERSED; }
    private static boolean compatibleBalance(BigDecimal a,BigDecimal b) {
        if(a.compareTo(b)==0)return true;
        BigDecimal coarse=a.scale()<b.scale()?a:b, fine=a.scale()<b.scale()?b:a;
        return coarse.scale()==0 && fine.setScale(0,RoundingMode.DOWN).compareTo(coarse)==0;
    }
    private void buildAccounts() {
        for(Entry e:entries) {
            Account a=accounts.computeIfAbsent(e.accountKey(),k->{Account v=new Account();v.key=k;v.label=e.t.displayAccount();v.kind=e.t.accountKind;v.identifierKnown=!"unknown".equals(e.t.account);return v;});
            for(Balance b:e.reports) {
                Balance prev=a.byType.get(b.type);
                if(prev==null||b.at>prev.at)a.byType.put(b.type,b);
                else if(b.at==prev.at&&b.value.compareTo(prev.value)!=0) { a.warnings.add("Different balances at the same time; latest balance ambiguous"); }
            }
            for(String w:e.warnings)if(w.startsWith("Conflicting reported"))a.warnings.add(w);
        }
        // These are tracked account slots, never sample balances.
        if(accounts.values().stream().noneMatch(a->"Bank".equals(a.kind)))placeholder("Centenary Bank:unknown","Centenary Bank • account unknown","Bank");
        if(accounts.values().stream().noneMatch(a->"Mobile Money".equals(a.kind)))placeholder("MTN:unknown","MTN Mobile Money • number unknown","Mobile Money");
        for(Account a:accounts.values()) {
            for(Balance b:a.byType.values())if(a.latest==null||b.at>a.latest.at || (b.at==a.latest.at&&"Available".equals(b.type)))a.latest=b;
            if(a.warnings.stream().anyMatch(w->w.contains("same time")))a.latest=null;
            List<Entry> rows=new ArrayList<>();for(Entry e:entries)if(e.accountKey().equals(a.key))rows.add(e);
            rows.sort(Comparator.comparingLong(e->e.occurredAt));
            Balance prior=null;
            for(Entry e:rows) {
                Balance b=null;for(Balance r:e.reports)if(b==null||r.at>b.at)b=r;
                if(b==null) {prior=null;continue;}
                if(prior!=null&&prior.type.equals(b.type)&&b.at>prior.at&&e.counted()&&e.fee!=null) {
                    BigDecimal expected=prior.value.add(e.credit()?e.amount:e.amount.negate()).subtract(e.fee);
                    if(expected.subtract(b.value).abs().compareTo(new BigDecimal("1"))>=0) {
                        String w="Reported balance difference UGX "+b.value.subtract(expected).toPlainString()+"; missing activity or a hold may explain it. No fee inferred.";
                        e.warnings.add(w);a.warnings.add(w);
                    }
                }
                prior=e.status==Transaction.Status.COMPLETED?b:null;
            }
        }
    }
    private void placeholder(String key,String label,String kind) { Account a=new Account();a.key=key;a.label=label;a.kind=kind;accounts.put(key,a); }
    private void flagPotentialDuplicates() {
        Map<String,Entry> previous=new HashMap<>();
        for(Entry e:entries)if(e.amount!=null) {
            String signature=e.accountKey()+"|"+e.direction+"|"+e.amount.stripTrailingZeros()+"|"+e.title;
            Entry old=previous.put(signature,e);
            if(old!=null&&Math.abs(e.occurredAt-old.occurredAt)<120000L&&(e.t.transactionId==null||old.t.transactionId==null)) {
                old.warnings.add("Possible duplicate without shared ID; kept separate for review");e.warnings.add("Possible duplicate without shared ID; kept separate for review");
            }
        }
    }
    public boolean matches(Entry e,long start,long end,String kind) { return e.occurredAt>=start&&e.occurredAt<end&&(kind.equals("All Accounts")||kind.equals(e.t.accountKind)); }
    public Totals totals(long start,long end,String kind) {
        Totals s=new Totals();
        for(Entry e:entries)if(matches(e,start,end,kind)) {
            if(e.status==Transaction.Status.PENDING)s.pending++;
            if(!e.counted()) {s.excluded++;}
            if(e.counted()) {
                s.count++;
                if(e.fee==null)s.unknownFees++;
                if(!e.internal) {
                    if(e.credit())s.income=s.income.add(e.amount);
                    else {s.principal=s.principal.add(e.amount);add(s.categories,e.category,e.amount);add(s.accounts,e.t.displayAccount(),e.amount);}
                }
            }
            // A reversal refunds principal, not necessarily its explicit fee.
            if(!e.conflict&&(e.status==Transaction.Status.COMPLETED||e.status==Transaction.Status.REVERSED)&&!e.feeRefunded&&e.fee!=null) {
                s.fees=s.fees.add(e.fee);add(s.categories,"Fees",e.fee);add(s.accounts,e.t.displayAccount(),e.fee);
            }
        }
        s.spending=s.principal.add(s.fees);s.net=s.income.subtract(s.spending);return s;
    }
    private static void add(Map<String,BigDecimal> map,String key,BigDecimal value) { if(value.signum()!=0)map.put(key,map.getOrDefault(key,ZERO).add(value)); }
}
