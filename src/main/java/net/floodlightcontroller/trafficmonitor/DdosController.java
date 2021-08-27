package net.floodlightcontroller.trafficmonitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.Link;

public class DdosController {
	private static final Logger logger = LoggerFactory.getLogger(DdosController.class);
	private static final String URL_ADD_DELETE_FLOW = "http://localhost:8080/wm/staticentrypusher/json";
	private static final String URL_TOPO_LINKS = "http://localhost:8080/wm/topology/links/json";
	private static int countFlow = 0;
	
	public static void executePolicy(IOFSwitchService switchService, ILinkDiscoveryService linkDiscoveryService, HashMap<NodePortTuple, SwitchPortStatistics> abnormalTraffic, HashMap<NodePortTuple, Date> addFlowEntryHistory, Policy policy, LinkedList<Event> events){		
		for(Entry<NodePortTuple, SwitchPortStatistics> e : abnormalTraffic.entrySet()){
			NodePortTuple npt = e.getKey();
			SwitchPortStatistics sps = e.getValue();
			IOFSwitch sw = switchService.getSwitch(npt.getNodeId());

			logger.info("ready to enter if");
			if(addFlowEntryHistory.isEmpty() || !addFlowEntryHistory.containsKey(npt)){	/* 下发历史为空 或者 没有下发过该流表项 */

				/* 根据配置策略执行系统动作 */
				switch(policy.getAction()){
				case Policy.ACTION_DROP:
					/* 如果出现异常的端口所连对端为终端主机，则下发流表  */
					if(!isPortConnectedToSwitch(sw.getId(), npt.getPortId(), linkDiscoveryService)){
						int hardTimeout = (int) policy.getActionDuration();
						dropPacket(sw, npt.getPortId().getPortNumber(), hardTimeout, countFlow++);					
						events.add(new Event(sps, policy));		/* 添加异常流量发生事件 */
						addFlowEntryHistory.put(npt, new Date());
					}
					break;
				default:
					logger.warn("undefined system action!");
					break;
				}				
			}else{	 /* 下发历史不为空且已经向该端口下发过流表项，检测该流表项是否过期 */		
				Date currentTime = new Date();
				long period = (currentTime.getTime() - addFlowEntryHistory.get(npt).getTime()) / 1000;	// 换算成second
				if(policy.getActionDuration() > 0  && period > policy.getActionDuration()){
					logger.info("flow {match:" + npt.getNodeId() + " / " + npt.getPortId() + ", action:drop} expired!");
					addFlowEntryHistory.remove(npt);
				}//if
			}//else
		}//for
	}
	
	/**
	 * 添加一条流表项，将匹配入端口的数据包进行丢弃
	 * @param sw
	 * @param ingressPortNo
	 * @param hardTimeout
	 * @param countFlow
	 */
	public static void dropPacket(IOFSwitch sw, int ingressPortNo, int hardTimeout, int countFlow){
		//解析传进来的属性
		HashMap<String, String> flow = new HashMap<String, String>();
		flow.put("switch", sw.getId().toString());
		flow.put("name", "flow");
		flow.put("in_port", String.valueOf(ingressPortNo));
		flow.put("cookie", "0");
		flow.put("priority", "32768");
		flow.put("active", "true");
		flow.put("actions", "output=no-forward");
		flow.put("hard_timeout", String.valueOf(hardTimeout));
		String result = addFlow(sw.getId().toString(), flow);
		logger.info(result);
	}

	public static String addFlow(String did,HashMap<String,String> flow)
	{
		String result = sendPost(URL_ADD_DELETE_FLOW,hashMapToJson(flow));
		return result;
	}
	
	public static String sendPost(String url, String param) {
        PrintWriter out = null;
        BufferedReader in = null;
        String result = "";
        try {
            URL realUrl = new URL(url);
            // 打开和URL之间的连接
            URLConnection conn = realUrl.openConnection();
            // 发送POST请求必须设置如下两行
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // flush输出流的缓冲
            out.flush();
            // 定义BufferedReader输入流来读取URL的响应
            in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                result += line;
            }
        } catch (Exception e) {
            System.out.println("发送 POST 请求出现异常！"+e);
            e.printStackTrace();
        }
        //使用finally块来关闭输出流、输入流
        finally{
            try{
                if(out!=null){
                    out.close();
                }
                if(in!=null){
                    in.close();
                }
            }
            catch(IOException ex){
                ex.printStackTrace();
            }
        }
        return result;
    }
	
	public static String hashMapToJson(HashMap map) {  
        String string = "{";  
        for (Iterator it = map.entrySet().iterator(); it.hasNext();) {  
            Entry e = (Entry) it.next();  
            string += "\"" + e.getKey() + "\":";  
            string += "\"" + e.getValue() + "\",";  
        }  
        string = string.substring(0, string.lastIndexOf(","));  
        string += "}";  
        logger.info(string);
        return string;
    } 


	/**
	 * 检查交换机sw端口相连的是否为交换机
	 * @return
	 */
	private static boolean isPortConnectedToSwitch(DatapathId dpid, OFPort port, ILinkDiscoveryService linkDiscoveryService){
		boolean result = false;
		
		Map linksMap = linkDiscoveryService.getLinks();	/* 获取交换机之间的链路 */
		Set keys = linksMap.keySet();
		Iterator it = keys.iterator();
		while(it.hasNext()){
			Link link = (Link)it.next();
			/* 检查该交换机链路中是否含有dpid和portNumber*/
			result = (link.getSrc().equals(dpid) && link.getSrcPort().equals(port)) || (link.getDst().equals(dpid) && link.getDstPort().equals(port) )? true : false;
		}
		return result;
	}
}


