package com.repeaterx.burp;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.repeaterx.ui.RepeaterXPanel;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ContextMenuHandler implements ContextMenuItemsProvider {
    private final RepeaterXPanel panel;

    public ContextMenuHandler(RepeaterXPanel panel) {
        this.panel = panel;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> items = new ArrayList<>();

        List<HttpRequestResponse> reqResps = new ArrayList<>(event.selectedRequestResponses());
        if (reqResps.isEmpty() && event.messageEditorRequestResponse().isPresent()) {
            HttpRequestResponse rr = event.messageEditorRequestResponse().get().requestResponse();
            if (rr != null) reqResps.add(rr);
        }
        if (reqResps.isEmpty()) return items;

        final List<HttpRequestResponse> finalReqResps = List.copyOf(reqResps);

        JMenuItem sendItem = new JMenuItem("Send to RepeaterX");
        sendItem.addActionListener(e -> {
            for (HttpRequestResponse rr : finalReqResps) {
                if (rr != null && rr.request() != null) {
                    sendToPanel(rr.request());
                }
            }
        });
        items.add(sendItem);

        return items;
    }

    private void sendToPanel(HttpRequest req) {
        String raw = new String(req.toByteArray().getBytes());
        String host = req.httpService() != null ? req.httpService().host() : "";
        int port = req.httpService() != null ? req.httpService().port() : 443;
        boolean https = req.httpService() != null && req.httpService().secure();
        SwingUtilities.invokeLater(() -> panel.sendToRepeaterX(raw, host, port, https));
    }
}
