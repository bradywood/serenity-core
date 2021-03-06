package net.thucydides.core.webdriver;

import com.gargoylesoftware.htmlunit.ScriptException;
import com.google.common.base.Optional;
import net.serenitybdd.core.pages.DefaultTimeouts;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.steps.StepEventBus;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.webdriver.stubs.*;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.HasInputDevices;
import org.openqa.selenium.interactions.Keyboard;
import org.openqa.selenium.interactions.Mouse;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * A proxy class for webdriver instances, designed to prevent the browser being opened unnecessarily.
 */
public class WebDriverFacade implements WebDriver, TakesScreenshot, HasInputDevices, JavascriptExecutor, HasCapabilities, ConfigurableTimeouts {

    private final Class<? extends WebDriver> driverClass;

    private final WebDriverFactory webDriverFactory;

    protected WebDriver proxiedWebDriver;

    private static final Logger LOGGER = LoggerFactory.getLogger(WebDriverFacade.class);

    private EnvironmentVariables environmentVariables;

    /**
     * Implicit timeout values recorded to that they can be restored after calling findElements()
     */
    protected Duration implicitTimeout;

    public WebDriverFacade(final Class<? extends WebDriver> driverClass,
                           final WebDriverFactory webDriverFactory) {
        this(driverClass, webDriverFactory, Injectors.getInjector().getInstance(EnvironmentVariables.class));
    }

    public WebDriverFacade(final Class<? extends WebDriver> driverClass,
                           final WebDriverFactory webDriverFactory,
                           final EnvironmentVariables environmentVariables ) {
        this.driverClass = driverClass;
        this.webDriverFactory = webDriverFactory;
        this.environmentVariables = environmentVariables;
        this.implicitTimeout = defaultImplicitWait();
    }

    public WebDriverFacade(final WebDriver driver,
                           final WebDriverFactory webDriverFactory,
                           final EnvironmentVariables environmentVariables ) {
        this.driverClass = driver.getClass();
        this.proxiedWebDriver = driver;
        this.webDriverFactory = webDriverFactory;
        this.environmentVariables = environmentVariables;
        this.implicitTimeout = defaultImplicitWait();
    }

    private Duration defaultImplicitWait() {
        int configuredWaitForTimeoutInMilliseconds = ThucydidesSystemProperty.WEBDRIVER_TIMEOUTS_IMPLICITLYWAIT
                        .integerFrom(environmentVariables, (int) DefaultTimeouts.DEFAULT_IMPLICIT_WAIT_TIMEOUT.in(MILLISECONDS));

       return new Duration(configuredWaitForTimeoutInMilliseconds, TimeUnit.MILLISECONDS);
    }

    public WebDriverFacade(final Class<? extends WebDriver> driverClass,
                           final WebDriverFactory webDriverFactory,
                           WebDriver proxiedWebDriver,
                           Duration implicitTimeout) {
        this.driverClass = driverClass;
        this.webDriverFactory = webDriverFactory;
        this.proxiedWebDriver = proxiedWebDriver;
        this.implicitTimeout = implicitTimeout;
    }

    public WebDriverFacade withTimeoutOf(Duration implicitTimeout) {
        return new WebDriverFacade(driverClass, webDriverFactory, proxiedWebDriver, implicitTimeout);
    }

    public Class<? extends WebDriver>  getDriverClass() {
        return driverClass;
    }

    public WebDriver getProxiedDriver() {
        if (proxiedWebDriver == null) {
            proxiedWebDriver = newProxyDriver();
            WebdriverProxyFactory.getFactory().notifyListenersOfWebdriverCreationIn(this);
        }
        return proxiedWebDriver;
    }

    public boolean isEnabled() {
        return !StepEventBus.getEventBus().webdriverCallsAreSuspended();
    }

    public void reset() {
        if (proxiedWebDriver != null) {
            forcedQuit();
        }
        proxiedWebDriver = null;

    }

    private void forcedQuit() {
        try {
            getDriverInstance().quit();
            proxiedWebDriver = null;
        } catch (WebDriverException e) {
            LOGGER.warn("Closing a driver that was already closed: " + e.getMessage());
        }
    }

    protected WebDriver newProxyDriver() {
        return newDriverInstance();
    }

    private WebDriver newDriverInstance() {
        try {
            if (StepEventBus.getEventBus().isDryRun()) {
                return new WebDriverStub();
            } else {
            webDriverFactory.setupFixtureServices();
            return webDriverFactory.newWebdriverInstance(driverClass);
            }
        } catch (UnsupportedDriverException e) {
            LOGGER.error("FAILED TO CREATE NEW WEBDRIVER_DRIVER INSTANCE " + driverClass + ": " + e.getMessage(), e);
            throw new UnsupportedDriverException("Could not instantiate " + driverClass, e);
        }
    }

    public <X> X getScreenshotAs(final OutputType<X> target) {
        if (proxyInstanciated() && driverCanTakeScreenshots()) {
            try {
                return ((TakesScreenshot) getProxiedDriver()).getScreenshotAs(target);
            } catch (WebDriverException e) {
                LOGGER.warn("Failed to take screenshot - driver closed already? (" + e.getMessage() + ")");
            } catch (OutOfMemoryError outOfMemoryError) {
                // Out of memory errors can happen with extremely big screens, and currently Selenium does
                // not handle them correctly/at all.
                LOGGER.error("Failed to take screenshot - out of memory", outOfMemoryError);
            }
        }
        return null;
    }

    private boolean driverCanTakeScreenshots() {
        return (TakesScreenshot.class.isAssignableFrom(getProxiedDriver().getClass()));
    }

    public void get(final String url) {
        if (!isEnabled()) {
            return;
        }
        openIgnoringHtmlUnitScriptErrors(url);
    }

    private void openIgnoringHtmlUnitScriptErrors(final String url) {
        try {
            getProxiedDriver().get(url);
            setTimeouts();
        } catch (WebDriverException e) {
            if (!htmlunitScriptError(e)) {
                throw e;
            }
        }
    }

    private void setTimeouts() {
        webDriverFactory.setTimeouts(getProxiedDriver(), implicitTimeout);
    }

    private boolean htmlunitScriptError(WebDriverException e) {
        if ((e.getCause() != null) && (e.getCause() instanceof ScriptException)) {
            LOGGER.warn("Ignoring HTMLUnit script error: " + e.getMessage());
            return true;
        } else {
            return false;
        }
    }

    public String getCurrentUrl() {
        if (!isEnabled()) {
            return StringUtils.EMPTY;
        }

        return getProxiedDriver().getCurrentUrl();
    }

    public String getTitle() {
        if (!isEnabled()) {
            return StringUtils.EMPTY;
        }

        return getProxiedDriver().getTitle();
    }

    public List<WebElement> findElements(final By by) {
        if (!isEnabled()) {
            return Collections.emptyList();
        }
        webDriverFactory.setTimeouts(getProxiedDriver(), getCurrentImplicitTimeout());
        List<WebElement> elements = getProxiedDriver().findElements(by);
        webDriverFactory.resetTimeouts(getProxiedDriver());
        return elements;
    }

    public WebElement findElement(final By by) {
        if (!isEnabled()) {
            return new WebElementFacadeStub();
        }
        webDriverFactory.setTimeouts(getProxiedDriver(), getCurrentImplicitTimeout());
        WebElement element = getProxiedDriver().findElement(by);
        webDriverFactory.resetTimeouts(getProxiedDriver());
        return element;
    }

    public String getPageSource() {
        if (!isEnabled()) {
            return StringUtils.EMPTY;
        }

        return getProxiedDriver().getPageSource();
    }

    public void setImplicitTimeout(Duration implicitTimeout) {
        webDriverFactory.setTimeouts(getProxiedDriver(), implicitTimeout);
    }

    public Duration getCurrentImplicitTimeout() {
        return webDriverFactory.currentTimeoutFor(getProxiedDriver());
    }

    public void resetTimeouts() {
        webDriverFactory.resetTimeouts(getProxiedDriver());
    }


    protected WebDriver getDriverInstance() {
        return proxiedWebDriver;
    }

    public void close() {
        if (proxyInstanciated()) {
        	//if there is only one window closing it means quitting the web driver
        	if (getDriverInstance().getWindowHandles() != null && getDriverInstance().getWindowHandles().size() == 1){
        		this.quit();
        	} else{
        		getDriverInstance().close();
        	}
            webDriverFactory.shutdownFixtureServices();
        }
    }

    public void quit() {
        if (proxyInstanciated()) {
            try {
                getDriverInstance().quit();
            } catch (WebDriverException e) {
                LOGGER.warn("Error while quitting the driver (" + e.getMessage() + ")");
            }
            proxiedWebDriver = null;
        }
    }

    protected boolean proxyInstanciated() {
        return (getDriverInstance() != null);
    }

    public Set<String> getWindowHandles() {
        if (!isEnabled()) {
            return new HashSet<String>();
        }

        return getProxiedDriver().getWindowHandles();
    }

    public String getWindowHandle() {
        if (!isEnabled()) {
            return StringUtils.EMPTY;
        }

        return getProxiedDriver().getWindowHandle();
    }

    public TargetLocator switchTo() {
        if (!isEnabled()) {
            return new TargetLocatorStub(this);
        }

        return getProxiedDriver().switchTo();
    }

    public Navigation navigate() {
        if (!isEnabled()) {
            return new NavigationStub();
        }

        return getProxiedDriver().navigate();
    }

    public Options manage() {
        if (!isEnabled()) {
            return new OptionsStub();
        }

        return new OptionsFacade(getProxiedDriver().manage(), this);
    }

    public boolean canTakeScreenshots() {
    	if (driverClass != null) {
    		if (driverClass == ProvidedDriver.class) {
    			return TakesScreenshot.class.isAssignableFrom(getProxiedDriver().getClass()) 
    					|| (getProxiedDriver().getClass() == RemoteWebDriver.class);
    		} else {
    			return TakesScreenshot.class.isAssignableFrom(driverClass)
    					|| (driverClass == RemoteWebDriver.class);
    		}
    	} else {
    		return false;
    	}
    }

    public boolean isInstantiated() {
        return (driverClass != null) && (proxiedWebDriver != null);
    }

    public Keyboard getKeyboard() {
        return ((HasInputDevices) getProxiedDriver()).getKeyboard();
    }

    public Mouse getMouse() {
        return ((HasInputDevices) getProxiedDriver()).getMouse();
    }

    public Object executeScript(String script, Object... parameters) {
        return ((JavascriptExecutor) getProxiedDriver()).executeScript(script, parameters);
    }

    public Object executeAsyncScript(String script, Object... parameters) {
        return ((JavascriptExecutor) getProxiedDriver()).executeAsyncScript(script, parameters);
    }

    @Override
    public Capabilities getCapabilities() {
        return ((HasCapabilities) getProxiedDriver()).getCapabilities();
    }

    public String getDriverName() {
        return SupportedWebDriver.forClass(getDriverClass()).name().toLowerCase();
    }
}
