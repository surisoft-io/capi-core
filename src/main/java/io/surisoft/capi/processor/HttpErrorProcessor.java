package io.surisoft.capi.processor;

import io.surisoft.capi.exception.AuthorizationException;
import io.surisoft.capi.utils.Constants;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.core5.http.NoHttpResponseException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public class HttpErrorProcessor implements Processor {

    @Override
    public void process(Exchange exchange) {
        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        if(cause instanceof SSLHandshakeException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_CERTIFICATE);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_CERTIFICATE);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        }
        if (cause instanceof SSLException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_CERTIFICATE);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_CERTIFICATE);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        } else if (cause instanceof UnknownHostException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_HOST);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_SERVICE_HOST);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        } else if (cause instanceof SocketTimeoutException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        } else if(cause instanceof HttpHostConnectException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_NO_SERVER_AVAILABLE);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_NO_SERVER_AVAILABLE);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        } else if(cause instanceof AuthorizationException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, cause.getMessage());
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, cause.getMessage());
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 401);
        } else if(cause instanceof NoHttpResponseException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_NOT_RESPONDING);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_NOT_RESPONDING);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        } else if(cause instanceof ConnectTimeoutException) {
            exchange.setProperty(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
            exchange.getIn().setHeader(Constants.REASON_MESSAGE_HEADER, Constants.ERROR_REMOTE_SERVER_TIMEOUT);
            exchange.getIn().setHeader(Constants.REASON_CODE_HEADER, 502);
        }
        exchange.getIn().setHeader(Constants.CAPI_URI_IN_ERROR, exchange.getIn().getHeader(Exchange.HTTP_URI).toString());
        exchange.getIn().setHeader(Constants.CAPI_URL_IN_ERROR, exchange.getIn().getHeader(Exchange.HTTP_URL).toString());
    }
}