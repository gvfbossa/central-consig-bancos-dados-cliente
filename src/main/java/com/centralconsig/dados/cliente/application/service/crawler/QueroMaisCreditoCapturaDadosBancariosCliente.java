package com.centralconsig.dados.cliente.application.service.crawler;

import com.centralconsig.core.application.service.ClienteService;
import com.centralconsig.core.application.service.DadosBancariosService;
import com.centralconsig.core.application.service.crawler.QueroMaisCreditoLoginService;
import com.centralconsig.core.application.service.crawler.UsuarioLoginQueroMaisCreditoService;
import com.centralconsig.core.application.service.crawler.WebDriverService;
import com.centralconsig.core.application.utils.CrawlerUtils;
import com.centralconsig.core.domain.entity.Cliente;
import com.centralconsig.core.domain.entity.DadosBancarios;
import com.centralconsig.core.domain.entity.UsuarioLoginQueroMaisCredito;
import com.google.common.util.concurrent.RateLimiter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class QueroMaisCreditoCapturaDadosBancariosCliente {

    private static final Logger log = LoggerFactory.getLogger(QueroMaisCreditoCapturaDadosBancariosCliente.class);

    private final WebDriverService webDriverService;
    private final QueroMaisCreditoLoginService queroMaisCreditoLoginService;
    private final ClienteService clienteService;
    private final DadosBancariosService dadosBancariosService;
    private final UsuarioLoginQueroMaisCreditoService usuarioLoginQueroMaisCreditoService;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public QueroMaisCreditoCapturaDadosBancariosCliente(WebDriverService webDriverService, QueroMaisCreditoLoginService queroMaisCreditoLoginService,
                                                        ClienteService clienteService, DadosBancariosService dadosBancariosService, UsuarioLoginQueroMaisCreditoService usuarioLoginQueroMaisCreditoService) {
        this.webDriverService = webDriverService;
        this.queroMaisCreditoLoginService = queroMaisCreditoLoginService;
        this.clienteService = clienteService;
        this.dadosBancariosService = dadosBancariosService;
        this.usuarioLoginQueroMaisCreditoService = usuarioLoginQueroMaisCreditoService;
    }

    @Scheduled(cron = "0 50 7-23 * * *", zone = "America/Sao_Paulo")
//    @Scheduled(fixedDelay = 1000)
    public void capturaDadosBancariosClientesAuto() {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Capturar Dados Bancários do Cliente já em execução. Ignorando nova tentativa.");
            return;
        }
        try {
            log.info("Capturar Dados Bancários do Cliente iniciado");
            capturaDadosBancariosClientes();
            log.info("Capturar Dados Bancários do Cliente finalizado");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Erro crítico ao Capturar Dados Bancários do Cliente. Erro: " + e.getMessage());
        } finally {
            isRunning.set(false);
            CrawlerUtils.killChromeDrivers();
        }
    }

    private void capturaDadosBancariosClientes() {
        List<UsuarioLoginQueroMaisCredito> usuariosDb = usuarioLoginQueroMaisCreditoService.retornaUsuariosParaCrawler();

        List<UsuarioLoginQueroMaisCredito> usuarios = new ArrayList<>();
        if (usuariosDb.size() < 8) {
            usuarios.addAll(usuariosDb);
            usuarios.addAll(usuariosDb);
        }
        else {
            usuarios.addAll(usuariosDb);
        }

        List<Cliente> clientes = clienteService.clientesFiltradosPorMargem().stream()
                .filter(cliente -> cliente.getDadosBancarios() == null)
                .collect(Collectors.toList());

        RateLimiter rateLimiter = RateLimiter.create(4.0);

        ExecutorService executor = Executors.newFixedThreadPool(usuarios.size());

        try {
            List<List<Cliente>> subListas = new ArrayList<>();
            for (int i = 0; i < clientes.size(); i += 120) {
                subListas.add(clientes.subList(i, Math.min(i + 120, clientes.size())));
            }

            LocalDateTime tempoFinal = LocalDateTime.now().plusMinutes(9);

            for (int i = 0; i < subListas.size(); i++) {
                final List<Cliente> subLista = subListas.get(i);
                final UsuarioLoginQueroMaisCredito usuario = usuarios.get(i % usuarios.size());

                executor.submit(() -> {
                    if (LocalDateTime.now().isBefore(tempoFinal)) {
                        for (Cliente cliente : subLista) {
                            if (LocalDateTime.now().isAfter(tempoFinal)) {
                                break;
                            }
                            crawlerConsultaDadosBancariosClientes(cliente, usuario, rateLimiter, tempoFinal);
                        }
                    }
                });
            }

            executor.shutdown();
            if (!executor.awaitTermination(540, TimeUnit.SECONDS)) {
                log.warn("Timeout atingido, forçando encerramento das tarefas.");
                executor.shutdownNow();
            }

        } catch (Exception e) {
            log.error("Erro na execução da captura em paralelo", e);
            executor.shutdownNow();
        } finally {
            CrawlerUtils.killChromeDrivers();
        }
    }

    private void crawlerConsultaDadosBancariosClientes(Cliente cliente, UsuarioLoginQueroMaisCredito usuario, RateLimiter rateLimiter, LocalDateTime tempoFinal) {
        WebDriver driver = null;
        try {
            rateLimiter.acquire();

            driver = webDriverService.criarDriver();
            WebDriverWait wait = webDriverService.criarWait(driver);

            if (!queroMaisCreditoLoginService.seleniumLogin(driver, usuario)) return;

            acessarTelaConsultaDadosBancarios(driver, wait);

            clicarCodCliente(driver, wait);

            Actions actions = new Actions(driver);

            CrawlerUtils.esperar(2);

            selecionarBulletEPreencherCpf(actions, cliente.getCpf());
            CrawlerUtils.interagirComAlert(driver);
            CrawlerUtils.esperar(2);

            selecionarCliente(actions);
            CrawlerUtils.esperar(2);

            acessarTelaDadosCliente(driver, wait);
            CrawlerUtils.esperar(2);

            DadosBancarios dados = extrairInfoDadosBancarios(driver, cliente);
            salvaDadosEAtualizaCliente(dados, cliente);
        } catch (Exception e) {
            log.error("Erro ao capturar dados bancários do cliente.");
        } finally {
            webDriverService.fecharDriver(driver);
        }
    }

    private void salvaDadosEAtualizaCliente(DadosBancarios dados, Cliente cliente) {
        cliente.setDadosBancarios(dados);
        clienteService.salvarOuAtualizarCliente(cliente);
        log.info("Dados Bancários do Cliente " + cliente.getCpf() + " salvos com sucesso!");
    }

    private DadosBancarios extrairInfoDadosBancarios(WebDriver driver, Cliente cliente) {
        String htmlCompleto = driver.getPageSource();

        String html = htmlCompleto.substring(
                htmlCompleto.indexOf("Referências Bancárias")
        );

        Document doc = Jsoup.parse(html);

        String banco = doc.select("#ctl00_Cph_UcCliente1_RefsBancarias_txtBanco_CAMPO").attr("value");
        String agencia = doc.select("#ctl00_Cph_UcCliente1_RefsBancarias_txtAgencia_CAMPO").attr("value");
        String conta = doc.select("#ctl00_Cph_UcCliente1_RefsBancarias_txtCC_CAMPO").attr("value");
        String dvConta = doc.select("#ctl00_Cph_UcCliente1_RefsBancarias_txtDvContaCorrente_CAMPO").attr("value");

        DadosBancarios dados = new DadosBancarios();
        dados.setCliente(cliente);
        dados.setBanco(banco);
        dados.setAgencia(agencia);
        dados.setConta(conta);
        dados.setDigitoConta(dvConta);

        return dados;
    }


    private void acessarTelaDadosCliente(WebDriver driver, WebDriverWait wait) {
        WebElement btnConfirmar = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//a[contains(text(),'Confirmar')]")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btnConfirmar);
    }

    private void selecionarCliente(Actions actions) {
        actions
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.ENTER)
                .perform();
    }

    private void selecionarBulletEPreencherCpf(Actions actions, String cpf) {
        actions
                .keyDown(Keys.SHIFT)
                .sendKeys(Keys.TAB)
                .keyUp(Keys.SHIFT)
                .sendKeys(Keys.ARROW_RIGHT)
                .sendKeys(Keys.ARROW_RIGHT)
                .sendKeys(Keys.TAB)
                .perform();

        actions
                .pause(500)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.BACK_SPACE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .sendKeys(Keys.DELETE)
                .perform();

        String cpfFormatado = String.format("%011d", Long.parseLong(cpf));

        for (char c : cpfFormatado.toCharArray()) {
            actions
                    .sendKeys(String.valueOf(c))
                    .pause(100);
        }
        actions.perform();

        actions
                .sendKeys(Keys.ENTER)
                .sendKeys(Keys.ENTER)
                .perform();
    }

    private void clicarCodCliente(WebDriver driver, WebDriverWait wait) {
        WebElement menuCadastro = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//a[contains(text(),'Cód. Cliente')]")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", menuCadastro);
    }

    private void acessarTelaConsultaDadosBancarios(WebDriver driver, WebDriverWait wait) {
        WebElement menuCadastro = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//a[contains(text(),'Cadastro')]")));

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", menuCadastro);

        WebElement opcaoCadastroCliente = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//a[contains(text(),'Cadastro de Cliente')]")));

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", opcaoCadastroCliente);

        WebElement opcaoFisico = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//a[contains(text(),'Físico')]")));

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", opcaoFisico);

        WebElement opcaoConsultar = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//a[contains(text(),'Consultar')]")));

        opcaoConsultar.click();
    }
}
