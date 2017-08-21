package com.samourai.sentinel;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AnticipateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
//import android.widget.Toast;
//import android.util.Log;

import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.core.Coin;

import com.samourai.sentinel.api.APIFactory;
import com.samourai.sentinel.api.Tx;
import com.samourai.sentinel.hd.HD_Wallet;
import com.samourai.sentinel.hd.HD_WalletFactory;
import com.samourai.sentinel.service.WebSocketService;
import com.samourai.sentinel.util.AddressFactory;
import com.samourai.sentinel.util.AppUtil;
import com.samourai.sentinel.util.BlockExplorerUtil;
import com.samourai.sentinel.util.DateUtil;
import com.samourai.sentinel.util.ExchangeRateFactory;
import com.samourai.sentinel.util.MonetaryUtil;
import com.samourai.sentinel.util.PrefsUtil;
import com.samourai.sentinel.util.TimeOutUtil;
import com.samourai.sentinel.util.TypefaceUtil;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import net.i2p.android.ext.floatingactionbutton.FloatingActionButton;
import net.i2p.android.ext.floatingactionbutton.FloatingActionsMenu;

public class BalanceActivity extends Activity {

    private static final int SCAN_URI = 2077;

    private LinearLayout tvBalanceBar = null;
    private TextView tvBalanceAmount = null;
    private TextView tvBalanceUnits = null;

    private ListView txList = null;
    private List<Tx> txs = null;
    private HashMap<String, Boolean> txStates = null;
    private TransactionAdapter txAdapter = null;
    private SwipeRefreshLayout swipeRefreshLayout = null;

    private FloatingActionsMenu ibQuickSend = null;
    private FloatingActionButton actionReceive = null;
    private FloatingActionButton actionXPUB = null;
//    private FloatingActionButton actionShapeShift = null;

    private boolean isBTC = true;

    public static final String ACTION_INTENT = "com.samourai.sentinel.BalanceFragment.REFRESH";

    protected BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if(ACTION_INTENT.equals(intent.getAction())) {

                BalanceActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        displayBalance();
                        refreshTx(false);
                    }
                });

            }

        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_balance);

        BalanceActivity.this.getActionBar().setTitle(R.string.app_name);

        LayoutInflater inflator = BalanceActivity.this.getLayoutInflater();
        tvBalanceBar = (LinearLayout)inflator.inflate(R.layout.balance_layout, null);
        tvBalanceBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                isBTC = (isBTC) ? false : true;
                displayBalance();
//                txAdapter.notifyDataSetChanged();
                return false;
            }
        });
        tvBalanceAmount = (TextView)tvBalanceBar.findViewById(R.id.BalanceAmount);
        tvBalanceUnits = (TextView)tvBalanceBar.findViewById(R.id.BalanceUnits);

        ibQuickSend = (FloatingActionsMenu)findViewById(R.id.wallet_menu);
        actionReceive = (FloatingActionButton)findViewById(R.id.receive);
        actionReceive.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {

                confirmAccountSelection();

            }
        });

        actionXPUB = (FloatingActionButton)findViewById(R.id.xpub);
        actionXPUB.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {

                TimeOutUtil.getInstance().updatePin();
                Intent intent = new Intent(BalanceActivity.this, XPUBListActivity.class);
                startActivity(intent);

            }
        });

        txList = (ListView)findViewById(R.id.txList);
        txAdapter = new TransactionAdapter();
        txList.setAdapter(txAdapter);
        txList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, final View view, int position, long id) {

                if(position == 0) {
                    return;
                }

                long viewId = view.getId();
                View v = (View)view.getParent();
                Tx tx = txs.get(position - 1);
                ImageView ivTxStatus = (ImageView)v.findViewById(R.id.TransactionStatus);
                TextView tvConfirmationCount = (TextView)v.findViewById(R.id.ConfirmationCount);

                if(viewId == R.id.ConfirmationCount || viewId == R.id.TransactionStatus) {

                    if(txStates.containsKey(tx.getHash()) && txStates.get(tx.getHash()) == true) {
                        txStates.put(tx.getHash(), false);
                        displayTxStatus(false, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                    }
                    else {
                        txStates.put(tx.getHash(), true);
                        displayTxStatus(true, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                    }

                }
                else {

                    String strTx = tx.getHash();
                    if(strTx != null) {
                        int sel = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BLOCK_EXPLORER, 0);
                        CharSequence url = BlockExplorerUtil.getInstance().getBlockExplorerUrls()[sel];

                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url + strTx));
                        startActivity(browserIntent);
                    }

                }

            }
        });

        swipeRefreshLayout = (SwipeRefreshLayout)findViewById(R.id.swiperefresh);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {

                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        refreshTx(true);
                    }
                });

            }
        });
        swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);

        refreshTx(false);

        if(!AppUtil.getInstance(BalanceActivity.this.getApplicationContext()).isServiceRunning(WebSocketService.class)) {
            BalanceActivity.this.startService(new Intent(BalanceActivity.this.getApplicationContext(), WebSocketService.class));
        }

    }

    @Override
    public void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter(ACTION_INTENT);
        LocalBroadcastManager.getInstance(BalanceActivity.this).registerReceiver(receiver, filter);

        displayBalance();
        refreshTx(false);

    }

    @Override
    public void onPause() {
        super.onPause();

        LocalBroadcastManager.getInstance(BalanceActivity.this).unregisterReceiver(receiver);
    }

    private class TransactionAdapter extends BaseAdapter {

        private LayoutInflater inflater = null;
        private static final int TYPE_ITEM = 0;
        private static final int TYPE_BALANCE = 1;

        TransactionAdapter() {
            inflater = (LayoutInflater)BalanceActivity.this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getCount() {
            if(txs == null) {
                txs = new ArrayList<Tx>();
                txStates = new HashMap<String, Boolean>();
            }
            return txs.size() + 1;
        }

        @Override
        public String getItem(int position) {
            if(txs == null) {
                txs = new ArrayList<Tx>();
                txStates = new HashMap<String, Boolean>();
            }
            if(position == 0) {
                return "";
            }
            return txs.get(position - 1).toString();
        }

        @Override
        public long getItemId(int position) {
            return position - 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_BALANCE : TYPE_ITEM;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public View getView(final int position, View convertView, final ViewGroup parent) {

            View view = null;

            int type = getItemViewType(position);
            if(convertView == null) {
                if(type == TYPE_BALANCE) {
                    view = tvBalanceBar;
                }
                else {
                    view = inflater.inflate(R.layout.tx_layout_simple, parent, false);
                }
            } else {
                view = convertView;
            }

            if(type == TYPE_BALANCE) {
                ;
            }
            else {
                view.findViewById(R.id.TransactionStatus).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ((ListView)parent).performItemClick(v, position, 0);
                    }
                });

                view.findViewById(R.id.ConfirmationCount).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ((ListView)parent).performItemClick(v, position, 0);
                    }
                });

                Tx tx = txs.get(position - 1);

                TextView tvTodayLabel = (TextView)view.findViewById(R.id.TodayLabel);
                String strDateGroup = DateUtil.getInstance(BalanceActivity.this).group(tx.getTS());
                if(position == 1) {
                    tvTodayLabel.setText(strDateGroup);
                    tvTodayLabel.setVisibility(View.VISIBLE);
                }
                else {
                    Tx prevTx = txs.get(position - 2);
                    String strPrevDateGroup = DateUtil.getInstance(BalanceActivity.this).group(prevTx.getTS());

                    if(strPrevDateGroup.equals(strDateGroup)) {
                        tvTodayLabel.setVisibility(View.GONE);
                    }
                    else {
                        tvTodayLabel.setText(strDateGroup);
                        tvTodayLabel.setVisibility(View.VISIBLE);
                    }
                }

                String strDetails = null;
                String strTS = DateUtil.getInstance(BalanceActivity.this).formatted(tx.getTS());
                long _amount = 0L;
                if(tx.getAmount() < 0.0) {
                    _amount = Math.abs((long)tx.getAmount());
                    strDetails = BalanceActivity.this.getString(R.string.you_sent);
                }
                else {
                    _amount = (long)tx.getAmount();
                    strDetails = BalanceActivity.this.getString(R.string.you_received);
                }
                String strAmount = getDisplayAmount(_amount);
                String strUnits = getDisplayUnits();

                TextView tvDirection = (TextView)view.findViewById(R.id.TransactionDirection);
                TextView tvDirection2 = (TextView)view.findViewById(R.id.TransactionDirection2);
                TextView tvDetails = (TextView)view.findViewById(R.id.TransactionDetails);
                ImageView ivTxStatus = (ImageView)view.findViewById(R.id.TransactionStatus);
                TextView tvConfirmationCount = (TextView)view.findViewById(R.id.ConfirmationCount);

                tvDirection.setTypeface(TypefaceUtil.getInstance(BalanceActivity.this).getAwesomeTypeface());
                if(tx.getAmount() < 0.0) {
                    tvDirection.setTextColor(Color.RED);
                    tvDirection.setText(Character.toString((char) TypefaceUtil.awesome_arrow_up));
                }
                else {
                    tvDirection.setTextColor(Color.GREEN);
                    tvDirection.setText(Character.toString((char) TypefaceUtil.awesome_arrow_down));
                }

                if(txStates.containsKey(tx.getHash()) && txStates.get(tx.getHash()) == false) {
                    txStates.put(tx.getHash(), false);
                    displayTxStatus(false, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                }
                else {
                    txStates.put(tx.getHash(), true);
                    displayTxStatus(true, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                }

                tvDirection2.setText(strDetails + " " + strAmount + " " + strUnits);
                tvDetails.setText(strTS);
            }

            return view;
        }

    }

    public void refreshTx(final boolean dragged) {

        final Handler handler = new Handler();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();

                int idx = SamouraiSentinel.getInstance(BalanceActivity.this).getCurrentSelectedAccount();

                List<String> _xpubs = new ArrayList<String>();
                _xpubs.addAll(SamouraiSentinel.getInstance(BalanceActivity.this).getXPUBs().keySet());
                _xpubs.addAll(SamouraiSentinel.getInstance(BalanceActivity.this).getLegacy().keySet());
                APIFactory.getInstance(BalanceActivity.this).getXPUB(_xpubs.toArray(new String[_xpubs.size()]));

                if(idx == 0)    {
                    txs = APIFactory.getInstance(BalanceActivity.this).getAllXpubTxs();
                }
                else    {
                    txs = APIFactory.getInstance(BalanceActivity.this).getXpubTxs().get(_xpubs.get(idx - 1));
                }

                try {
                    if(HD_WalletFactory.getInstance(BalanceActivity.this).get() != null)    {

                        HD_Wallet hdw = HD_WalletFactory.getInstance(BalanceActivity.this).get();

                        for(int i = 0; i < hdw.getAccounts().size(); i++)   {
                            HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(i).getReceive().setAddrIdx(AddressFactory.getInstance().getHighestTxReceiveIdx(i));
//                            HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(i).getChange().setAddrIdx(AddressFactory.getInstance().getHighestTxChangeIdx(i));
                        }

                    }
                }
                catch(IOException ioe) {
                    ;
                }
                catch(MnemonicException.MnemonicLengthException mle) {
                    ;
                }

                PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.FIRST_RUN, false);

                handler.post(new Runnable() {
                    public void run() {
                        if(dragged)    {
                            swipeRefreshLayout.setRefreshing(false);
                        }
                        txAdapter.notifyDataSetChanged();
                        displayBalance();
                    }
                });

                Looper.loop();

            }
        }).start();

    }

    public void displayBalance() {
        String strFiat = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.CURRENT_FIAT, "USD");
        double btc_fx = ExchangeRateFactory.getInstance(BalanceActivity.this).getAvgPrice(strFiat);

        int idx = SamouraiSentinel.getInstance(BalanceActivity.this).getCurrentSelectedAccount();
        long balance = 0L;

        List<String> _xpubs = new ArrayList<String>();
        _xpubs.addAll(SamouraiSentinel.getInstance(BalanceActivity.this).getXPUBs().keySet());
        _xpubs.addAll(SamouraiSentinel.getInstance(BalanceActivity.this).getLegacy().keySet());
        if(idx == 0)    {
            balance = APIFactory.getInstance(BalanceActivity.this).getXpubBalance();
        }
        else if(_xpubs.get(idx - 1) != null && APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(_xpubs.get(idx - 1)) != null)    {
            if(APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(_xpubs.get(idx - 1)) != null)    {
                balance = APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(_xpubs.get(idx - 1));
            }
        }
        else    {
            ;
        }

        double btc_balance = (((double)balance) / 1e8);
        double fiat_balance = btc_fx * btc_balance;

        if(isBTC) {
            tvBalanceAmount.setText(getDisplayAmount(balance));
            tvBalanceUnits.setText(getDisplayUnits());
        }
        else {
            tvBalanceAmount.setText(MonetaryUtil.getInstance().getFiatFormat(strFiat).format(fiat_balance));
            tvBalanceUnits.setText(strFiat);
        }

    }

    public String getDisplayAmount(long value) {

        String strAmount = null;
        DecimalFormat df = new DecimalFormat("#");
        df.setMinimumIntegerDigits(1);
        df.setMinimumFractionDigits(1);
        df.setMaximumFractionDigits(8);

        int unit = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC);
        switch(unit) {
            case MonetaryUtil.MICRO_BTC:
                strAmount = df.format(((double)(value * 1000000L)) / 1e8);
                break;
            case MonetaryUtil.MILLI_BTC:
                strAmount = df.format(((double)(value * 1000L)) / 1e8);
                break;
            default:
                strAmount = Coin.valueOf(value).toPlainString();
                break;
        }

        return strAmount;
    }

    public String getDisplayUnits() {

        return (String) MonetaryUtil.getInstance().getBTCUnits()[PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC)];

    }

    private void displayTxStatus(boolean heads, long confirmations, TextView tvConfirmationCount, ImageView ivTxStatus)	{

        if(heads)	{
            if(confirmations == 0) {
                rotateTxStatus(tvConfirmationCount, true);
                ivTxStatus.setVisibility(View.VISIBLE);
                ivTxStatus.setImageResource(R.drawable.ic_query_builder_white);
                tvConfirmationCount.setVisibility(View.GONE);
            }
            else if(confirmations > 3) {
                rotateTxStatus(tvConfirmationCount, true);
                ivTxStatus.setVisibility(View.VISIBLE);
                ivTxStatus.setImageResource(R.drawable.ic_done_white);
                tvConfirmationCount.setVisibility(View.GONE);
            }
            else {
                rotateTxStatus(ivTxStatus, false);
                tvConfirmationCount.setVisibility(View.VISIBLE);
                tvConfirmationCount.setText(Long.toString(confirmations));
                ivTxStatus.setVisibility(View.GONE);
            }
        }
        else	{
            if(confirmations < 100) {
                rotateTxStatus(ivTxStatus, false);
                tvConfirmationCount.setVisibility(View.VISIBLE);
                tvConfirmationCount.setText(Long.toString(confirmations));
                ivTxStatus.setVisibility(View.GONE);
            }
            else    {
                rotateTxStatus(ivTxStatus, false);
                tvConfirmationCount.setVisibility(View.VISIBLE);
                tvConfirmationCount.setText("\u221e");
                ivTxStatus.setVisibility(View.GONE);
            }
        }

    }

    private void rotateTxStatus(View view, boolean clockwise)	{

        float degrees = 360f;
        if(!clockwise)	{
            degrees = -360f;
        }

        ObjectAnimator animation = ObjectAnimator.ofFloat(view, "rotationY", 0.0f, degrees);
        animation.setDuration(1000);
        animation.setRepeatCount(0);
        animation.setInterpolator(new AnticipateInterpolator());
        animation.start();
    }

    private void confirmAccountSelection()	{

        final Set<String> xpubKeys = SamouraiSentinel.getInstance(BalanceActivity.this).getXPUBs().keySet();
        final Set<String> legacyKeys = SamouraiSentinel.getInstance(BalanceActivity.this).getLegacy().keySet();
        final List<String> xpubList = new ArrayList<String>();
        xpubList.addAll(xpubKeys);
        xpubList.addAll(legacyKeys);

        if(xpubList.size() == 1)    {
            SamouraiSentinel.getInstance(BalanceActivity.this).setCurrentSelectedAccount(1);
            Intent intent = new Intent(BalanceActivity.this, ReceiveActivity.class);
            startActivity(intent);
            return;
        }

        final String[] accounts = new String[xpubList.size()];
        for(int i = 0; i < xpubList.size(); i++)   {
            if(xpubList.get(i).startsWith("xpub"))    {
                accounts[i] = SamouraiSentinel.getInstance(BalanceActivity.this).getXPUBs().get(xpubList.get(i));
            }
            else    {
                accounts[i] = SamouraiSentinel.getInstance(BalanceActivity.this).getLegacy().get(xpubList.get(i));
            }
        }

        int sel = SamouraiSentinel.getInstance(BalanceActivity.this).getCurrentSelectedAccount() == 0 ? 0 : SamouraiSentinel.getInstance(BalanceActivity.this).getCurrentSelectedAccount() - 1;

        new AlertDialog.Builder(BalanceActivity.this)
                .setTitle(R.string.deposit_into)
                .setSingleChoiceItems(accounts, sel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {

                                dialog.dismiss();

                                SamouraiSentinel.getInstance(BalanceActivity.this).setCurrentSelectedAccount(which + 1);

                                Intent intent = new Intent(BalanceActivity.this, ReceiveActivity.class);
                                startActivity(intent);

                            }
                        }
                ).show();

    }

}