import {
  CheckCircleFilled,
  LockOutlined,
  MailOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Card, Form, Input, Typography, message } from "antd";
import { useState } from "react";
import { flushSync } from "react-dom";
import { signup } from "../utils";

const { Paragraph, Text, Title } = Typography;

const SignupForm = ({ onShowLogin }) => {
  const [loading, setLoading] = useState(false);
  const [accountCreated, setAccountCreated] = useState(false);
  const [form] = Form.useForm();

  const handleContinueToLogin = () => {
    setAccountCreated(false);
    form.resetFields();
    onShowLogin();
  };

  const onFinish = (data) => {
    const feedbackKey = "signup-status";
    setLoading(true);
    message.loading({
      content: "Creating your account...",
      duration: 0,
      key: feedbackKey,
    });

    signup(data)
      .then(() => {
        form.resetFields();
        flushSync(() => {
          setAccountCreated(true);
        });
        message.success({
          content: "Account Created",
          duration: 2,
          key: feedbackKey,
        });
      })
      .catch((err) => {
        message.error({
          content: err.message,
          key: feedbackKey,
        });
      })
      .finally(() => {
        setLoading(false);
      });
  };

  return (
    <Card className="auth-card signup-card">
      {accountCreated ? (
        <div className="signup-success">
          <CheckCircleFilled className="signup-success__icon" />
          <Text className="signup-success__eyebrow">Registration complete</Text>
          <Title level={3} className="signup-success__title">
            Account Created
          </Title>
          <Paragraph className="signup-success__text">
            Your customer profile is ready. Sign in now to start adding items,
            paying, and placing orders.
          </Paragraph>
          <Button
            className="auth-submit signup-success__button"
            type="primary"
            block
            onClick={handleContinueToLogin}
          >
            Continue to sign in
          </Button>
        </div>
      ) : (
        <>
          <Text className="auth-card__eyebrow">Create account</Text>
          <Title level={3} className="auth-card__title">
            Register directly in the storefront.
          </Title>
          <Paragraph className="auth-card__text">
            Create a customer profile without leaving the page, then sign in and
            continue through the cart and payment flow.
          </Paragraph>

          <Form form={form} name="register" onFinish={onFinish} layout="vertical" preserve={false}>
            <Form.Item
              label="Email"
              name="email"
              rules={[{ required: true, message: "Please input your email" }]}
            >
              <Input className="auth-input" prefix={<MailOutlined />} placeholder="you@example.com" />
            </Form.Item>

            <Form.Item
              label="Password"
              name="password"
              rules={[{ required: true, message: "Please input your password" }]}
            >
              <Input.Password
                className="auth-input"
                prefix={<LockOutlined />}
                placeholder="Password"
              />
            </Form.Item>

            <Form.Item
              label="First name"
              name="first_name"
              rules={[{ required: true, message: "Please input your first name" }]}
            >
              <Input className="auth-input" prefix={<UserOutlined />} placeholder="First name" />
            </Form.Item>

            <Form.Item
              label="Last name"
              name="last_name"
              rules={[{ required: true, message: "Please input your last name" }]}
            >
              <Input className="auth-input" prefix={<UserOutlined />} placeholder="Last name" />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Button
                className="auth-submit"
                type="primary"
                htmlType="button"
                loading={loading}
                onClick={() => form.submit()}
                block
              >
                Create account
              </Button>
            </Form.Item>
          </Form>

          <div className="auth-card__switch">
            <Text className="auth-card__switch-text">Already have an account?</Text>
            <Button className="auth-card__switch-button" type="link" onClick={onShowLogin}>
              Sign in
            </Button>
          </div>
        </>
      )}
    </Card>
  );
};

export default SignupForm;
