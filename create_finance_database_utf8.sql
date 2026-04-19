-- ============================================
-- 财务会计系统数据库初始化脚本
-- 数据库: finance
-- 编码: utf8mb4 (支持中文和emoji)
-- ============================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS finance 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE finance;

-- ============================================
-- 1. 基础数据表
-- ============================================

-- 1.1 会计科目表
CREATE TABLE IF NOT EXISTS accounting_subjects (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '科目ID',
    subject_code VARCHAR(20) NOT NULL UNIQUE COMMENT '科目编码',
    subject_name VARCHAR(100) NOT NULL COMMENT '科目名称',
    parent_id BIGINT DEFAULT 0 COMMENT '父级科目ID，0表示顶级科目',
    level INT NOT NULL DEFAULT 1 COMMENT '科目层级',
    subject_type VARCHAR(20) NOT NULL COMMENT '科目类型: ASSET-资产, LIABILITY-负债, EQUITY-所有者权益, COST-成本, INCOME-收入, EXPENSE-费用',
    balance_direction VARCHAR(10) NOT NULL COMMENT '余额方向: DEBIT-借方, CREDIT-贷方',
    is_cash_equivalent TINYINT DEFAULT 0 COMMENT '是否为现金等价物: 0-否, 1-是',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_parent_id (parent_id),
    INDEX idx_subject_type (subject_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会计科目表';

-- 1.2 部门表
CREATE TABLE IF NOT EXISTS departments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '部门ID',
    dept_code VARCHAR(20) NOT NULL UNIQUE COMMENT '部门编码',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id BIGINT DEFAULT 0 COMMENT '上级部门ID，0表示顶级部门',
    manager_id BIGINT COMMENT '部门负责人ID',
    dept_type VARCHAR(20) COMMENT '部门类型: HEADQUARTERS-总部, BRANCH-分公司, DEPARTMENT-部门',
    cost_center_code VARCHAR(20) COMMENT '成本中心编码',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_parent_id (parent_id),
    INDEX idx_manager_id (manager_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部门表';

-- 1.3 员工表
CREATE TABLE IF NOT EXISTS employees (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '员工ID',
    emp_no VARCHAR(20) NOT NULL UNIQUE COMMENT '员工工号',
    emp_name VARCHAR(50) NOT NULL COMMENT '员工姓名',
    dept_id BIGINT COMMENT '所属部门ID',
    position VARCHAR(50) COMMENT '职位',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    entry_date DATE COMMENT '入职日期',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-在职, 0-离职',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_dept_id (dept_id),
    INDEX idx_emp_no (emp_no),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工表';

-- 1.4 客户表
CREATE TABLE IF NOT EXISTS customers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '客户ID',
    customer_code VARCHAR(20) NOT NULL UNIQUE COMMENT '客户编码',
    customer_name VARCHAR(200) NOT NULL COMMENT '客户名称',
    customer_type VARCHAR(20) COMMENT '客户类型: ENTERPRISE-企业, INDIVIDUAL-个人, GOVERNMENT-政府',
    tax_number VARCHAR(50) COMMENT '纳税人识别号',
    contact_person VARCHAR(50) COMMENT '联系人',
    contact_phone VARCHAR(20) COMMENT '联系电话',
    address VARCHAR(500) COMMENT '地址',
    credit_limit DECIMAL(15,2) DEFAULT 0 COMMENT '信用额度',
    payment_terms INT COMMENT '付款账期(天)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-停用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_customer_code (customer_code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户表';

-- 1.5 供应商表
CREATE TABLE IF NOT EXISTS suppliers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '供应商ID',
    supplier_code VARCHAR(20) NOT NULL UNIQUE COMMENT '供应商编码',
    supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称',
    supplier_type VARCHAR(20) COMMENT '供应商类型: MATERIAL-原材料, SERVICE-服务, EQUIPMENT-设备',
    tax_number VARCHAR(50) COMMENT '纳税人识别号',
    contact_person VARCHAR(50) COMMENT '联系人',
    contact_phone VARCHAR(20) COMMENT '联系电话',
    address VARCHAR(500) COMMENT '地址',
    bank_account VARCHAR(50) COMMENT '银行账号',
    bank_name VARCHAR(100) COMMENT '开户银行',
    payment_terms INT COMMENT '付款账期(天)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-停用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_supplier_code (supplier_code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商表';

-- ============================================
-- 2. 银行账户与资金表
-- ============================================

-- 2.1 银行账户表
CREATE TABLE IF NOT EXISTS bank_accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '账户ID',
    account_code VARCHAR(20) NOT NULL UNIQUE COMMENT '账户编码',
    account_name VARCHAR(100) NOT NULL COMMENT '账户名称',
    bank_name VARCHAR(100) NOT NULL COMMENT '开户银行',
    account_number VARCHAR(50) NOT NULL COMMENT '银行账号',
    account_type VARCHAR(20) NOT NULL COMMENT '账户类型: BASIC-基本户, GENERAL-一般户, SPECIAL-专户',
    currency VARCHAR(10) NOT NULL DEFAULT 'CNY' COMMENT '币种: CNY-人民币, USD-美元, EUR-欧元',
    opening_balance DECIMAL(15,2) DEFAULT 0 COMMENT '期初余额',
    current_balance DECIMAL(15,2) DEFAULT 0 COMMENT '当前余额',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-正常, 0-冻结',
    opened_date DATE COMMENT '开户日期',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_account_code (account_code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='银行账户表';

-- 2.2 资金流水表
CREATE TABLE IF NOT EXISTS fund_transactions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '流水ID',
    transaction_no VARCHAR(50) NOT NULL UNIQUE COMMENT '流水号',
    account_id BIGINT NOT NULL COMMENT '银行账户ID',
    transaction_date DATE NOT NULL COMMENT '交易日期',
    transaction_type VARCHAR(20) NOT NULL COMMENT '交易类型: DEPOSIT-存款, WITHDRAWAL-取款, TRANSFER_IN-转入, TRANSFER_OUT-转出',
    amount DECIMAL(15,2) NOT NULL COMMENT '交易金额',
    balance_after DECIMAL(15,2) COMMENT '交易后余额',
    counterparty_name VARCHAR(200) COMMENT '对方户名',
    counterparty_account VARCHAR(50) COMMENT '对方账号',
    summary VARCHAR(500) COMMENT '摘要说明',
    voucher_id BIGINT COMMENT '关联凭证ID',
    created_by BIGINT COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_account_id (account_id),
    INDEX idx_transaction_date (transaction_date),
    INDEX idx_voucher_id (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资金流水表';

-- ============================================
-- 3. 应收应付管理表
-- ============================================

-- 3.1 应收账款表
CREATE TABLE IF NOT EXISTS accounts_receivable (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '应收ID',
    ar_no VARCHAR(50) NOT NULL UNIQUE COMMENT '应收单号',
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    invoice_no VARCHAR(50) COMMENT '发票号码',
    invoice_date DATE COMMENT '开票日期',
    total_amount DECIMAL(15,2) NOT NULL COMMENT '应收总额',
    received_amount DECIMAL(15,2) DEFAULT 0 COMMENT '已收金额',
    outstanding_amount DECIMAL(15,2) NOT NULL COMMENT '未收金额',
    due_date DATE COMMENT '到期日期',
    aging_days INT COMMENT '账龄天数',
    status VARCHAR(20) NOT NULL DEFAULT 'UNPAID' COMMENT '状态: UNPAID-未结清, PARTIAL-部分收款, PAID-已结清, OVERDUE-逾期',
    remarks VARCHAR(500) COMMENT '备注',
    created_by BIGINT COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_customer_id (customer_id),
    INDEX idx_ar_no (ar_no),
    INDEX idx_status (status),
    INDEX idx_due_date (due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应收账款表';

-- 3.2 应付账款表
CREATE TABLE IF NOT EXISTS accounts_payable (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '应付ID',
    ap_no VARCHAR(50) NOT NULL UNIQUE COMMENT '应付单号',
    supplier_id BIGINT NOT NULL COMMENT '供应商ID',
    invoice_no VARCHAR(50) COMMENT '发票号码',
    invoice_date DATE COMMENT '开票日期',
    total_amount DECIMAL(15,2) NOT NULL COMMENT '应付总额',
    paid_amount DECIMAL(15,2) DEFAULT 0 COMMENT '已付金额',
    outstanding_amount DECIMAL(15,2) NOT NULL COMMENT '未付金额',
    due_date DATE COMMENT '到期日期',
    aging_days INT COMMENT '账龄天数',
    status VARCHAR(20) NOT NULL DEFAULT 'UNPAID' COMMENT '状态: UNPAID-未结清, PARTIAL-部分付款, PAID-已结清, OVERDUE-逾期',
    remarks VARCHAR(500) COMMENT '备注',
    created_by BIGINT COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_supplier_id (supplier_id),
    INDEX idx_ap_no (ap_no),
    INDEX idx_status (status),
    INDEX idx_due_date (due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应付账款表';

-- ============================================
-- 4. 凭证与账务表
-- ============================================

-- 4.1 会计凭证表
CREATE TABLE IF NOT EXISTS vouchers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '凭证ID',
    voucher_no VARCHAR(50) NOT NULL UNIQUE COMMENT '凭证号',
    voucher_date DATE NOT NULL COMMENT '凭证日期',
    voucher_type VARCHAR(20) NOT NULL COMMENT '凭证类型: RECEIPT-收款, PAYMENT-付款, TRANSFER-转账, ADJUSTMENT-调整',
    summary VARCHAR(500) COMMENT '凭证摘要',
    attachment_count INT DEFAULT 0 COMMENT '附件数量',
    prepared_by BIGINT COMMENT '制单人ID',
    reviewed_by BIGINT COMMENT '审核人ID',
    posted_by BIGINT COMMENT '过账人ID',
    posted_date DATETIME COMMENT '过账时间',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT-草稿, REVIEWED-已审核, POSTED-已过账, CANCELLED-已作废',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_voucher_no (voucher_no),
    INDEX idx_voucher_date (voucher_date),
    INDEX idx_status (status),
    INDEX idx_prepared_by (prepared_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会计凭证表';

-- 4.2 凭证明细表
CREATE TABLE IF NOT EXISTS voucher_entries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分录ID',
    voucher_id BIGINT NOT NULL COMMENT '凭证ID',
    entry_no INT NOT NULL COMMENT '分录序号',
    subject_id BIGINT NOT NULL COMMENT '会计科目ID',
    debit_amount DECIMAL(15,2) DEFAULT 0 COMMENT '借方金额',
    credit_amount DECIMAL(15,2) DEFAULT 0 COMMENT '贷方金额',
    department_id BIGINT COMMENT '部门ID',
    employee_id BIGINT COMMENT '员工ID',
    customer_id BIGINT COMMENT '客户ID',
    supplier_id BIGINT COMMENT '供应商ID',
    summary VARCHAR(500) COMMENT '分录摘要',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_voucher_id (voucher_id),
    INDEX idx_subject_id (subject_id),
    INDEX idx_department_id (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='凭证明细表';

-- 4.3 总账汇总表
CREATE TABLE IF NOT EXISTS general_ledger_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '汇总ID',
    period VARCHAR(7) NOT NULL COMMENT '会计期间(YYYY-MM)',
    subject_id BIGINT NOT NULL COMMENT '会计科目ID',
    opening_balance DECIMAL(15,2) DEFAULT 0 COMMENT '期初余额',
    debit_total DECIMAL(15,2) DEFAULT 0 COMMENT '借方发生额合计',
    credit_total DECIMAL(15,2) DEFAULT 0 COMMENT '贷方发生额合计',
    closing_balance DECIMAL(15,2) DEFAULT 0 COMMENT '期末余额',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_period_subject (period, subject_id),
    INDEX idx_period (period),
    INDEX idx_subject_id (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='总账汇总表';

-- ============================================
-- 5. 固定资产管理表
-- ============================================

-- 5.1 固定资产表
CREATE TABLE IF NOT EXISTS fixed_assets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '资产ID',
    asset_code VARCHAR(20) NOT NULL UNIQUE COMMENT '资产编码',
    asset_name VARCHAR(200) NOT NULL COMMENT '资产名称',
    asset_category VARCHAR(50) COMMENT '资产类别: BUILDING-房屋建筑物, MACHINE-机器设备, VEHICLE-运输工具, ELECTRONIC-电子设备, OTHER-其他',
    specification VARCHAR(200) COMMENT '规格型号',
    manufacturer VARCHAR(100) COMMENT '生产厂家',
    purchase_date DATE NOT NULL COMMENT '购置日期',
    purchase_price DECIMAL(15,2) NOT NULL COMMENT '购置原值',
    useful_life_months INT COMMENT '预计使用年限(月)',
    residual_value_rate DECIMAL(5,2) DEFAULT 5 COMMENT '残值率(%)',
    depreciation_method VARCHAR(20) DEFAULT 'STRAIGHT_LINE' COMMENT '折旧方法: STRAIGHT_LINE-直线法, DOUBLE_DECLINING-双倍余额递减法',
    accumulated_depreciation DECIMAL(15,2) DEFAULT 0 COMMENT '累计折旧',
    net_value DECIMAL(15,2) COMMENT '净值',
    department_id BIGINT COMMENT '使用部门ID',
    location VARCHAR(200) COMMENT '存放地点',
    custodian_id BIGINT COMMENT '保管人ID',
    status VARCHAR(20) NOT NULL DEFAULT 'IN_USE' COMMENT '状态: IN_USE-在用, IDLE-闲置, SCRAPPED-报废, SOLD-出售',
    scrap_date DATE COMMENT '报废日期',
    remarks VARCHAR(500) COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_asset_code (asset_code),
    INDEX idx_department_id (department_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='固定资产表';

-- 5.2 固定资产折旧表
CREATE TABLE IF NOT EXISTS asset_depreciation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '折旧记录ID',
    asset_id BIGINT NOT NULL COMMENT '资产ID',
    period VARCHAR(7) NOT NULL COMMENT '折旧期间(YYYY-MM)',
    monthly_depreciation DECIMAL(15,2) NOT NULL COMMENT '本月折旧额',
    accumulated_depreciation DECIMAL(15,2) NOT NULL COMMENT '累计折旧',
    net_value DECIMAL(15,2) NOT NULL COMMENT '净值',
    depreciation_date DATE NOT NULL COMMENT '折旧日期',
    voucher_id BIGINT COMMENT '关联凭证ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_asset_period (asset_id, period),
    INDEX idx_period (period),
    INDEX idx_voucher_id (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='固定资产折旧表';

-- ============================================
-- 6. 费用报销表
-- ============================================

-- 6.1 费用报销单表
CREATE TABLE IF NOT EXISTS expense_claims (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '报销单ID',
    claim_no VARCHAR(50) NOT NULL UNIQUE COMMENT '报销单号',
    employee_id BIGINT NOT NULL COMMENT '申请人ID',
    department_id BIGINT COMMENT '申请部门ID',
    claim_date DATE NOT NULL COMMENT '申请日期',
    total_amount DECIMAL(15,2) NOT NULL COMMENT '报销总额',
    approved_amount DECIMAL(15,2) DEFAULT 0 COMMENT '批准金额',
    paid_amount DECIMAL(15,2) DEFAULT 0 COMMENT '已付金额',
    payment_account_id BIGINT COMMENT '付款账户ID',
    payment_date DATE COMMENT '付款日期',
    approver_id BIGINT COMMENT '审批人ID',
    approval_date DATETIME COMMENT '审批时间',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT-草稿, SUBMITTED-已提交, APPROVED-已批准, REJECTED-已拒绝, PAID-已付款',
    rejection_reason VARCHAR(500) COMMENT '拒绝原因',
    remarks VARCHAR(500) COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_claim_no (claim_no),
    INDEX idx_employee_id (employee_id),
    INDEX idx_department_id (department_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用报销单表';

-- 6.2 费用报销明细表
CREATE TABLE IF NOT EXISTS expense_claim_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '明细ID',
    claim_id BIGINT NOT NULL COMMENT '报销单ID',
    expense_date DATE NOT NULL COMMENT '费用发生日期',
    expense_type VARCHAR(50) NOT NULL COMMENT '费用类型: TRAVEL-差旅费, MEAL-餐费, TRANSPORT-交通费, ACCOMMODATION-住宿费, OFFICE-办公费, OTHER-其他',
    amount DECIMAL(15,2) NOT NULL COMMENT '费用金额',
    description VARCHAR(500) COMMENT '费用说明',
    invoice_no VARCHAR(50) COMMENT '发票号码',
    attachment_count INT DEFAULT 0 COMMENT '附件数量',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_claim_id (claim_id),
    INDEX idx_expense_type (expense_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用报销明细表';

-- ============================================
-- 7. 财务报表表
-- ============================================

-- 7.1 资产负债表
CREATE TABLE IF NOT EXISTS balance_sheet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '报表ID',
    period VARCHAR(7) NOT NULL COMMENT '会计期间(YYYY-MM)',
    report_date DATE NOT NULL COMMENT '报表日期',
    item_code VARCHAR(20) NOT NULL COMMENT '项目编码',
    item_name VARCHAR(100) NOT NULL COMMENT '项目名称',
    item_type VARCHAR(20) NOT NULL COMMENT '项目类型: ASSET-资产, LIABILITY-负债, EQUITY-所有者权益',
    beginning_balance DECIMAL(18,2) DEFAULT 0 COMMENT '年初余额',
    ending_balance DECIMAL(18,2) DEFAULT 0 COMMENT '期末余额',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_period_item (period, item_code),
    INDEX idx_period (period),
    INDEX idx_item_type (item_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资产负债表';

-- 7.2 利润表
CREATE TABLE IF NOT EXISTS income_statement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '报表ID',
    period VARCHAR(7) NOT NULL COMMENT '会计期间(YYYY-MM)',
    report_date DATE NOT NULL COMMENT '报表日期',
    item_code VARCHAR(20) NOT NULL COMMENT '项目编码',
    item_name VARCHAR(100) NOT NULL COMMENT '项目名称',
    item_type VARCHAR(20) NOT NULL COMMENT '项目类型: REVENUE-收入, COST-成本, EXPENSE-费用, PROFIT-利润',
    current_amount DECIMAL(18,2) DEFAULT 0 COMMENT '本期金额',
    previous_amount DECIMAL(18,2) DEFAULT 0 COMMENT '上期金额',
    year_to_date DECIMAL(18,2) DEFAULT 0 COMMENT '本年累计',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_period_item (period, item_code),
    INDEX idx_period (period),
    INDEX idx_item_type (item_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='利润表';

-- 7.3 现金流量表
CREATE TABLE IF NOT EXISTS cash_flow_statement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '报表ID',
    period VARCHAR(7) NOT NULL COMMENT '会计期间(YYYY-MM)',
    report_date DATE NOT NULL COMMENT '报表日期',
    item_code VARCHAR(20) NOT NULL COMMENT '项目编码',
    item_name VARCHAR(100) NOT NULL COMMENT '项目名称',
    activity_type VARCHAR(20) NOT NULL COMMENT '活动类型: OPERATING-经营活动, INVESTING-投资活动, FINANCING-筹资活动',
    inflow_amount DECIMAL(18,2) DEFAULT 0 COMMENT '现金流入',
    outflow_amount DECIMAL(18,2) DEFAULT 0 COMMENT '现金流出',
    net_amount DECIMAL(18,2) DEFAULT 0 COMMENT '净额',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_period_item (period, item_code),
    INDEX idx_period (period),
    INDEX idx_activity_type (activity_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='现金流量表';

-- ============================================
-- 8. 税务管理表
-- ============================================

-- 8.1 增值税发票表
CREATE TABLE IF NOT EXISTS vat_invoices (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '发票ID',
    invoice_no VARCHAR(50) NOT NULL UNIQUE COMMENT '发票号码',
    invoice_code VARCHAR(20) COMMENT '发票代码',
    invoice_type VARCHAR(20) NOT NULL COMMENT '发票类型: SPECIAL-专用发票, NORMAL-普通发票, ELECTRONIC-电子发票',
    invoice_date DATE NOT NULL COMMENT '开票日期',
    seller_name VARCHAR(200) NOT NULL COMMENT '销售方名称',
    seller_tax_number VARCHAR(50) COMMENT '销售方税号',
    buyer_name VARCHAR(200) NOT NULL COMMENT '购买方名称',
    buyer_tax_number VARCHAR(50) COMMENT '购买方税号',
    total_amount DECIMAL(15,2) NOT NULL COMMENT '价税合计',
    excluding_tax_amount DECIMAL(15,2) NOT NULL COMMENT '不含税金额',
    tax_amount DECIMAL(15,2) NOT NULL COMMENT '税额',
    tax_rate DECIMAL(5,2) COMMENT '税率(%)',
    status VARCHAR(20) NOT NULL DEFAULT 'VALID' COMMENT '状态: VALID-有效, INVALID-作废, RED-红冲',
    verification_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '认证状态: PENDING-待认证, VERIFIED-已认证, EXPIRED-已过期',
    verification_date DATE COMMENT '认证日期',
    remarks VARCHAR(500) COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_invoice_no (invoice_no),
    INDEX idx_invoice_date (invoice_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='增值税发票表';

-- 8.2 纳税申报表
CREATE TABLE IF NOT EXISTS tax_returns (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '申报ID',
    period VARCHAR(7) NOT NULL COMMENT '纳税期间(YYYY-MM)',
    tax_type VARCHAR(20) NOT NULL COMMENT '税种: VAT-增值税, CIT-企业所得税, IIT-个人所得税, OTHER-其他',
    taxable_amount DECIMAL(18,2) DEFAULT 0 COMMENT '应纳税额',
    deducted_amount DECIMAL(18,2) DEFAULT 0 COMMENT '减免税额',
    actual_tax DECIMAL(18,2) DEFAULT 0 COMMENT '实缴税额',
    declaration_date DATE COMMENT '申报日期',
    payment_date DATE COMMENT '缴款日期',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待申报, DECLARED-已申报, PAID-已缴纳, OVERDUE-逾期',
    remarks VARCHAR(500) COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_period_tax (period, tax_type),
    INDEX idx_tax_type (tax_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='纳税申报表';

-- ============================================
-- 9. 预算管理与审计日志
-- ============================================

-- 9.1 预算表
CREATE TABLE IF NOT EXISTS budgets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '预算ID',
    budget_year INT NOT NULL COMMENT '预算年度',
    budget_month INT COMMENT '预算月份(NULL表示年度预算)',
    department_id BIGINT COMMENT '部门ID',
    subject_id BIGINT COMMENT '科目ID',
    budget_amount DECIMAL(15,2) NOT NULL COMMENT '预算金额',
    actual_amount DECIMAL(15,2) DEFAULT 0 COMMENT '实际发生额',
    variance_amount DECIMAL(15,2) DEFAULT 0 COMMENT '差异金额',
    variance_rate DECIMAL(5,2) DEFAULT 0 COMMENT '差异率(%)',
    status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' COMMENT '状态: DRAFT-草稿, SUBMITTED-已提交, APPROVED-已批准, ADJUSTED-已调整',
    approved_by BIGINT COMMENT '审批人ID',
    approved_date DATETIME COMMENT '审批时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_budget_unique (budget_year, budget_month, department_id, subject_id),
    INDEX idx_budget_year (budget_year),
    INDEX idx_department_id (department_id),
    INDEX idx_subject_id (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预算表';

-- 9.2 审计日志表
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    user_id BIGINT COMMENT '操作用户ID',
    username VARCHAR(50) COMMENT '用户名',
    operation_type VARCHAR(50) NOT NULL COMMENT '操作类型: CREATE-创建, UPDATE-更新, DELETE-删除, APPROVE-审批, POST-过账',
    module VARCHAR(50) NOT NULL COMMENT '模块名称',
    record_id BIGINT COMMENT '记录ID',
    old_values JSON COMMENT '修改前值',
    new_values JSON COMMENT '修改后值',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '用户代理',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_operation_type (operation_type),
    INDEX idx_module (module),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- ============================================
-- 初始化基础数据
-- ============================================

-- 插入示例会计科目
INSERT INTO accounting_subjects (subject_code, subject_name, parent_id, level, subject_type, balance_direction, is_cash_equivalent, sort_order) VALUES
('1001', '库存现金', 0, 1, 'ASSET', 'DEBIT', 1, 1),
('1002', '银行存款', 0, 1, 'ASSET', 'DEBIT', 1, 2),
('1122', '应收账款', 0, 1, 'ASSET', 'DEBIT', 0, 3),
('1403', '原材料', 0, 1, 'ASSET', 'DEBIT', 0, 4),
('1601', '固定资产', 0, 1, 'ASSET', 'DEBIT', 0, 5),
('2001', '短期借款', 0, 1, 'LIABILITY', 'CREDIT', 0, 6),
('2202', '应付账款', 0, 1, 'LIABILITY', 'CREDIT', 0, 7),
('4001', '实收资本', 0, 1, 'EQUITY', 'CREDIT', 0, 8),
('5001', '主营业务收入', 0, 1, 'INCOME', 'CREDIT', 0, 9),
('5401', '主营业务成本', 0, 1, 'COST', 'DEBIT', 0, 10),
('6601', '销售费用', 0, 1, 'EXPENSE', 'DEBIT', 0, 11),
('6602', '管理费用', 0, 1, 'EXPENSE', 'DEBIT', 0, 12);

-- 插入示例部门
INSERT INTO departments (dept_code, dept_name, parent_id, dept_type, cost_center_code, sort_order) VALUES
('DEPT001', '财务部', 0, 'DEPARTMENT', 'CC001', 1),
('DEPT002', '销售部', 0, 'DEPARTMENT', 'CC002', 2),
('DEPT003', '采购部', 0, 'DEPARTMENT', 'CC003', 3),
('DEPT004', '人力资源部', 0, 'DEPARTMENT', 'CC004', 4),
('DEPT005', '生产部', 0, 'DEPARTMENT', 'CC005', 5);

-- ============================================
-- 完成提示
-- ============================================
SELECT '✅ 财务会计数据库创建成功！' AS message;
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'finance';
